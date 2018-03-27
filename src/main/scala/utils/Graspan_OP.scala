package utils

import java.util

import org.apache.spark.{HashPartitioner, RangePartitioner, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileUtil, Path}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

/**
  * Created by cycy on 2018/1/29.
  */
object Graspan_OP extends Para {
  /**
    * 预处理图的方法
    */
  def processGrammar(grammar_origin:List[Array[String]],input_grammar:String)
  :(Map[String,EdgeLabel],Int,Int,List[EdgeLabel],Map[EdgeLabel,EdgeLabel],List[((EdgeLabel,EdgeLabel),EdgeLabel)])={
    val symbol_Map=grammar_origin.flatMap(s=>s.toList).distinct.zipWithIndex.toMap
    val (loop:List[EdgeLabel],directadd:Map[EdgeLabel,EdgeLabel],grammar:List[((EdgeLabel,EdgeLabel),EdgeLabel)])={
      if(input_grammar.contains("pointsto")){
        //        println("Grammar need preprocessed")
        (grammar_origin.filter(s=>s.length==1).map(s=>symbol_Map.getOrElse(s(0),-1)),grammar_origin.filter(s=>s
          .length==2)
          .map(s=>(symbol_Map.getOrElse(s(1),-1),symbol_Map.getOrElse(s(0),-1))).toMap,grammar_origin.filter(s=>s
          .length==3).map(s=>((symbol_Map.getOrElse(s(1),-1),symbol_Map.getOrElse(s(2),-1)),symbol_Map.getOrElse(s(0)
          ,-1))))
      }
      else (List(),Map(),grammar_origin.map(s=>((symbol_Map.getOrElse(s(1),-1),symbol_Map.getOrElse(s(2),-1)),symbol_Map.getOrElse(s(0),-1))))
    }
    val symbol_num=symbol_Map.size
    val symbol_num_bitsize=HBase_OP.getIntBit(symbol_num)
    (symbol_Map,symbol_num,symbol_num_bitsize,loop,directadd,grammar)
  }
  def processLinux(sc:SparkContext,input_graph:String,input_grammar:String,output:String,par:Int): Unit ={
    val configuration = new Configuration()
    val input = new Path(input_graph)
    val hdfs = input.getFileSystem(configuration)
    val fs = hdfs.listStatus(input)
    val fileName = FileUtil.stat2Paths(fs)

    var start=0L
    for(i<-fileName){
      println(i.toString+" is processing")
      println("start:     \t"+start)
      val tmp_graph=sc.textFile(i.toString,par).filter(!_.trim.equals("")).map(s=>{
        val str=s.split("\\s+")
        (str(0).toInt,str(1).toInt,str(2))
      })
      val nodes_order=tmp_graph.flatMap(s=>List(s._1,s._2)).distinct()
      println("nodes num: \t"+nodes_order.count())

      val all=tmp_graph.map(s=>(s._1+start,s._2+start,s._3))
      val rdd=all.flatMap(s=>List(s._1,s._2)).distinct()
      println("transfer:  \t"+rdd.count)
      println("min : " + rdd.min())
      println("max : "+rdd.max())

    all.map(s=>(s._1+"\t"+s._2+"\t"+s._3)).repartition(1)
        .saveAsTextFile (output+"/"+i.toString.split("/").last)
      start=start+nodes_order.count()
      println("start:     \t"+start)
    }
  }
  def processGraph(sc:SparkContext,input_graph:String,file_index_f:Int,fine_index_b:Int,input_grammar:String,
                   symbol_Map:Map[String,EdgeLabel],
                   loop:List[EdgeLabel],
                   directadd:Map[EdgeLabel,EdgeLabel],par:Int):(RDD[(VertexId,VertexId,EdgeLabel)],Int,Int)={
    val input_str:String={
      if(input_graph.contains("Linux")){
        val configuration = new Configuration()
        val input = new Path(input_graph)
        val hdfs = input.getFileSystem(configuration)
        val fs = hdfs.listStatus(input)
        val fileName = FileUtil.stat2Paths(fs)
        var tmpstr:String=fileName(file_index_f).toString
        println(tmpstr)
        for(i<- file_index_f+1 to fine_index_b){
          println(fileName(i))
          tmpstr += ","+fileName(i)
        }
        tmpstr
      }
      else input_graph
    }
    val graph_changelabel:RDD[(VertexId,VertexId,EdgeLabel)]=
      sc.textFile(input_str,par).filter(!_.trim.equals("")).map (s=>{
      val str=s.split("\\s+")
      (str(0).toInt,str(1).toInt,symbol_Map.getOrElse(str(2),-1))
    }).repartition(par).setName("graph_changelabel").cache()
    println("graph_origin: "+graph_changelabel.count())
    val nodes=graph_changelabel.flatMap(s=>List(s._1,s._2)).distinct()
    val graph={
      if(input_grammar.contains("pointsto")){
        println("Graph need preprocessed")
        (graph_changelabel
          ++ nodes.flatMap(s=>loop.map(x=>(s,s,x)))
          ++ graph_changelabel.filter(s=>directadd.contains(s._3)).map(s=>(s._1,s._2,directadd.getOrElse(s._3,-1)))
          ).distinct()
      }
      else graph_changelabel.map(s=>(s._1,s._2,s._3)).distinct()
    }.cache()
    if(graph.filter(s=>s._3== -1).isEmpty()==false) println("读取EdgeLabel出错")
    val nodes_totalnum=nodes.count().toInt
    val nodes_num_bitsize=HBase_OP.getIntBit(nodes_totalnum)
    (graph,nodes_num_bitsize,nodes_totalnum)
  }

  def processDF(sc:SparkContext,input_graph:String,output:String,par:Int):Unit={
    val graph=sc.textFile(input_graph,par).filter(s=>s.trim!="").map(s=>s.split("\\s+").map(_.trim))
    graph.filter(_(2)=="e").map(s=>s(0)+"\t"+s(1)+"\t1").repartition(1).saveAsTextFile(output+"/e")
    graph.filter(_(2)=="n").map(s=>s(0)+"\t"+s(1)+"\t0").repartition(1).saveAsTextFile(output+"/n")
  }
  /**
    * Join操作一
    */
  def join(mid_adj_list:List[(VertexId,List[((VertexId,VertexId),EdgeLabel,Boolean)])],
           grammar:List[((EdgeLabel, EdgeLabel),EdgeLabel)],
           directadd:Map[EdgeLabel,EdgeLabel])
  :(List[(VertexId,VertexId,EdgeLabel)],String)={
    val t0=System.nanoTime():Double
    var res_edges:List[(VertexId,VertexId,EdgeLabel)]=List()
    var tmp_str:String=""
    mid_adj_list.map(s=>{
      val flag=s._1
      val list=s._2
      val edge_before=list.filter(x=>x._1._2==flag).map(x=>(x._2,x._1._1,x._3)).groupBy(_._1)
        .map(x=>(x._1,x._2.map(y=>(y._2,y._3))))
      val edge_after=list.filter(x=>x._1._1==flag).map(x=>(x._2,x._1._2,x._3)).groupBy(_._1)
        .map(x=>(x._1,x._2.map(y=>(y._2,y._3))))
      for(i<-grammar){
        val (f,b)=i._1
        val res_label=i._2
        val f_list=edge_before.getOrElse(f,List())
        val b_list=edge_after.getOrElse(b,List())
        if(f_list.length<b_list.length){
          for(j<-f_list){
            if(j._2==false){
              res_edges ++= b_list.filter(x=>x._2).map(x=>(j._1,x._1,res_label)).distinct
            }
            else res_edges ++= b_list.map(x=>(j._1,x._1,res_label)).distinct
          }
        }
        else{
          for(j<-b_list){
            if(j._2==false){
              res_edges ++= f_list.filter(x=>x._2).map(x=>(x._1,j._1,res_label)).distinct
            }
            else res_edges ++= f_list.map(x=>(x._1,j._1,res_label)).distinct
          }
        }
      }
    })
    //MAM	AMs V DV
    //    tmp_str+="\nMAM: "+res_edges.filter(s=>s._3==5).length+", "
    //    tmp_str+="AMs: "+res_edges.filter(s=>s._3==6).length+", "
    //    tmp_str+="V: "+res_edges.filter(s=>s._3==4).length+", "
    //    tmp_str+="DV: "+res_edges.filter(s=>s._3==1).length+", "
    //    tmp_str+="M: "+res_edges.filter(s=>s._3==0).length+"\n"

    val old_num=res_edges.length
    val add_edges=res_edges.filter(s=>directadd.contains(s._3)).map(s=>(s._1,s._2,directadd.getOrElse(s
      ._3,-1)))
    res_edges=(res_edges ++ add_edges).distinct
    val t1=System.nanoTime():Double
    println()
    println("|| input edges: "+mid_adj_list.map(s=>s._2.length).sum
      +",\torigin newedges: "+old_num
      +",\tadd_newedges: "+add_edges.length
      +",\tdistinct newedges: " +res_edges.length+" ||"
      +"join take time:"+((t1-t0) /1000000000.0)+" secs")
    tmp_str+=("|| input edges: "+mid_adj_list.map(s=>s._2.length).sum
      +",\torigin newedges: "+old_num
      +",\tadd_newedges: "+add_edges.length
      +",\tdistinct newedges: " +res_edges.length+" ||"
      +"join take time:"+((t1-t0) /1000000000.0)+" secs")
    (res_edges,tmp_str)
  }
  def computeInPartition_completely(step:Int,index:Int,
                                    mid_adj:Iterator[(VertexId,List[((VertexId,VertexId),EdgeLabel, Boolean)])],
                                    grammar:List[((EdgeLabel,EdgeLabel),EdgeLabel)],
                                    htable_name:String,
                                    nodes_num_bitsize:Int,symbol_num_bitsize:Int,
                                    directadd:Map[EdgeLabel,EdgeLabel],
                                    is_complete_loop:Boolean,max_complete_loop_turn:Int,max_delta:Int,
                                    htable_split_Map:Map[Int,String],
                                    htable_nodes_interval:Int,
                                    Hbase_interval:Int,
                                    default_split:String)
  :Iterator[(List[(VertexId,VertexId,EdgeLabel)],List[String])]={

    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var recording:List[String]=List()
    val mid_adj_list=mid_adj.toList
    //    var old_edges:List[(VertexId,VertexId,EdgeLabel)]=mid_adj_list.flatMap(s=>(s._2)).map(s=>(s._1._1,s._1._2,s._2))
    println("At STEP "+step+", partition "+index+",\tedges sum to "+mid_adj_list.map(s=>s._2.length).sum)
    recording:+="At STEP "+step+", partition "+index+",\tedges sum to "+mid_adj_list.map(s=>s._2.length).sum
    var (res_edges,tmp_str)=join(mid_adj_list,grammar,directadd)
    recording:+=tmp_str
    //    res_edges=res_edges.filter(s=> !old_edges.contains(s))
    //    println("res_edges distinct: "+res_edges.length)
    //    recording:+="res_edges distinct: "+res_edges.length
    /**
      * form clousure
      * only focused on edges from key inpartition or to key inpartition
      */
    //    if(is_complete_loop){
    //      val key_Set=mid_adj_list.map(s=>s._1).toSet
    //      var continue:Boolean=is_complete_loop
    //      var oldedges:List[(VertexId,VertexId,EdgeLabel,Boolean)]=old_edges.map(s=>(s._1,s._2,s._3,false))
    //      var newedges:List[(VertexId,VertexId,EdgeLabel,Boolean)]=res_edges.map(s=>(s._1,s._2,s._3,true))
    //      val first_new_num=newedges.length
    //      val max_loop=max_complete_loop_turn
    //      var turn=0
    //      while(continue){
    //        println("start loop ")
    //        tmp_str+="start loop "
    //        val t0=System.nanoTime():Double
    //        turn+=1
    //        val m_a_l=(oldedges ++ newedges).flatMap(s=>List((s._1,((s._1,s._2),s._3,s._4)),(s._2,((s._1,s._2),s._3,s._4)))
    //        ).groupBy(_._1).toList.map(s=>(s._1,s._2.map(x=>x._2)))
    //        val edges_before=(oldedges ++ newedges).map(s=>(s._1,s._2,s._3))
    //        val (tmp_edges,tmp_str_inloop)=join(m_a_l,grammar,directadd)
    //        tmp_str+=tmp_str_inloop
    //        //      tmp_str+="bfore filter: "+tmp.length
    //        oldedges=oldedges ++newedges.map(s=>(s._1,s._2,s._3,false))
    //        //过滤新边，只保留与本partition有关的新边
    //        newedges=tmp_edges.filter(s=>(key_Set.contains(s._1)||key_Set.contains(s._2))&&edges_before.contains(s)==false)
    //          .map(s=>(s._1,s._2,s._3,true))
    //        continue= (turn<max_loop && !newedges.isEmpty && oldedges.length-first_new_num<max_delta)
    //        val t1=System.nanoTime():Double
    //        println("complete_loop take time: "+((t1-t0)/ 1000000000.0/60).formatted("%.3f") + " min")
    //        if(continue==false){
    //          println("end loop")
    //          tmp_str+="end loop"
    //          recording:+=tmp_str
    //          res_edges=oldedges.map(s=>(s._1,s._2,s._3))
    //          println("after complete loop, res_edges= "+res_edges.length)
    //          recording:+="after complete loop, res_edges= "+res_edges.length
    //        }
    //      }
    //    }
    /**
      * 多线程开启
      */
    //    val executors = Executors.newCachedThreadPool()
    //    val thread = new MyThread
    //    class MyThread extends Thread{
    //      override def run(): Unit = {
    //
    //      }
    //    }
    //    executors.submit(thread)
    /**
      * Hbase过滤
      */
    t0=System.nanoTime():Double
    val len=res_edges.length
    res_edges= {
      val res=HBase_OP.queryHbase_inPartition(res_edges,nodes_num_bitsize,symbol_num_bitsize,htable_name,
        htable_split_Map,
        htable_nodes_interval,
        Hbase_interval,default_split)
      //      recording:+="res_edges confirmed new by Hbase: "+res.length
      res
    }
    t1=System.nanoTime():Double
    println("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    recording:+=("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    List((res_edges,recording)).toIterator
  }

  /**
    * Join操作，分离旧边和新边，不再使用新旧边的标志位
    */
  def join_2(mid_adj_list:List[(VertexId,(List[((VertexId,VertexId),EdgeLabel)],List[((VertexId,VertexId),EdgeLabel)]))],
             grammar:List[((EdgeLabel, EdgeLabel),EdgeLabel)],
             directadd:Map[EdgeLabel,EdgeLabel])
  :(List[(VertexId,VertexId,EdgeLabel)],String,Long)={
    val t0=System.nanoTime():Double
    var res_edges:List[(VertexId,VertexId,EdgeLabel)]=List()
    var tmp_str:String=""
    var origin_formedgesnum:Long=0L
    mid_adj_list.foreach(s=>{
      val flag=s._1
      val old_list:List[((VertexId,VertexId),EdgeLabel)]=s._2._1
      val new_list:List[((VertexId,VertexId),EdgeLabel)]=s._2._2
        /**
          * 1、新边之间的两两连接
          */
        val new_edge_before = new_list.filter(x => x._1._2 == flag).map(x => (x._2, x._1._1)).groupBy(_._1)
          .map(x => (x._1, x._2.map(y => (y._2))))
        val new_edge_after = new_list.filter(x => x._1._1 == flag).map(x => (x._2, x._1._2)).groupBy(_._1)
          .map(x => (x._1, x._2.map(y => (y._2))))
        grammar.foreach(i=>{
          val (f, b) = i._1
          val res_label = i._2
          val f_list = new_edge_before.getOrElse(f, List())
          val b_list = new_edge_after.getOrElse(b, List())
          origin_formedgesnum += f_list.length * b_list.length
          if (f_list.length < b_list.length) {
            for (j <- f_list) {
              res_edges ++= b_list.map(x => (j, x, res_label)).distinct
            }
          }
          else {
            for (j <- b_list) {
              res_edges ++= f_list.map(x => (x, j, res_label)).distinct
            }
          }
        })

        /**
          * 2、旧边与新边的连接
          */
      if(old_list.length!=0){
        val old_edge_before = old_list.filter(x => x._1._2 == flag).map(x => (x._2, x._1._1)).groupBy(_._1)
          .map(x => (x._1, x._2.map(y => (y._2))))
        val old_edge_after = old_list.filter(x => x._1._1 == flag).map(x => (x._2, x._1._2)).groupBy(_._1)
          .map(x => (x._1, x._2.map(y => (y._2))))

        for (i <- grammar) {
          val (f, b) = i._1
          val res_label = i._2
          //nf , ob
          val new_f_list = new_edge_before.getOrElse(f, List())
          val old_b_list = old_edge_after.getOrElse(b, List())
          origin_formedgesnum += new_f_list.length * old_b_list.length
          if (new_f_list.length < old_b_list.length) {
            for (j <- new_f_list) {
              res_edges ++= old_b_list.map(x => (j, x, res_label)).distinct
            }
          }
          else {
            for (j <- old_b_list) {
              res_edges ++= new_f_list.map(x => (x, j, res_label)).distinct
            }
          }
          //of , nb
          val old_f_list = old_edge_before.getOrElse(f, List())
          val new_b_list = new_edge_after.getOrElse(b, List())
          origin_formedgesnum += old_f_list.length * new_b_list.length
          if (old_f_list.length < new_b_list.length) {
            for (j <- old_f_list) {
              res_edges ++= new_b_list.map(x => (j, x, res_label)).distinct
            }
          }
          else {
            for (j <- new_b_list) {
              res_edges ++= old_f_list.map(x => (x, j, res_label)).distinct
            }
          }
        }
      }
    })

    val old_num=res_edges.length
    val add_edges=res_edges.filter(s=>directadd.contains(s._3)).map(s=>(s._1,s._2,directadd.getOrElse(s
      ._3,-1)))
    res_edges=(res_edges ++ add_edges).distinct
    val t1=System.nanoTime():Double
    val toolong={
      if((t1-t0) /1000000000.0<10) "normal"
      else if((t1-t0) /1000000000.0 <100) "longer than 10"
      else "longer than 100"
    }
    val old_list_num=mid_adj_list.map(s=>s._2._1.length).sum
    val new_list_num=mid_adj_list.map(s=>s._2._2.length).sum
    println()
    println("|| "
      +",\told_list: "+old_list_num
      +",\tnew_list: "+new_list_num
      +",\torigin_formedges: "+origin_formedgesnum
      +",\torigin newedges: "+old_num
      +",\tadd_newedges: "+add_edges.length
      +",\tdistinct newedges: " +res_edges.length+" ||"
      +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
    tmp_str+=("|| "
      +",\told_list: "+old_list_num
      +",\tnew_list: "+new_list_num
      +",\torigin_formedges: "+origin_formedgesnum
      +",\torigin newedges: "+old_num
      +",\tadd_newedges: "+add_edges.length
      +",\tdistinct newedges: " +res_edges.length+" ||"
      +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
    (res_edges,tmp_str,origin_formedgesnum)
  }

  def computeInPartition_completely_2(step:Int,index:Int,
                                      mid_adj:Iterator[(VertexId,(List[((VertexId,VertexId),EdgeLabel)],List[((VertexId,VertexId),EdgeLabel)]))],
                                      grammar:List[((EdgeLabel,EdgeLabel),EdgeLabel)],
                                      htable_name:String,
                                      nodes_num_bitsize:Int,symbol_num_bitsize:Int,
                                      directadd:Map[EdgeLabel,EdgeLabel],
                                      is_complete_loop:Boolean,max_complete_loop_turn:Int,max_delta:Int,
                                      htable_split_Map:Map[Int,String],
                                      htable_nodes_interval:Int,
                                      Hbase_interval:Int,
                                      default_split:String)
  :Iterator[(List[(VertexId,VertexId,EdgeLabel)],List[String],Long)]={

    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var recording:List[String]=List()
    val mid_adj_list=mid_adj.toList
    //    var old_edges:List[(VertexId,VertexId,EdgeLabel)]=mid_adj_list.flatMap(s=>(s._2)).map(s=>(s._1._1,s._1._2,s._2))
        println("At STEP "+step+", partition "+index)
        recording:+="At STEP "+step+", partition "+index
    var (res_edges,tmp_str,coarest_num)=join_2(mid_adj_list,grammar,directadd)
    recording:+=tmp_str
    //    res_edges=res_edges.filter(s=> !old_edges.contains(s))
    //    println("res_edges distinct: "+res_edges.length)
    //    recording:+="res_edges distinct: "+res_edges.length
    /**
      * form clousure
      * only focused on edges from key inpartition or to key inpartition
      */
    //    if(is_complete_loop){
    //      val key_Set=mid_adj_list.map(s=>s._1).toSet
    //      var continue:Boolean=is_complete_loop
    //      var oldedges:List[(VertexId,VertexId,EdgeLabel,Boolean)]=old_edges.map(s=>(s._1,s._2,s._3,false))
    //      var newedges:List[(VertexId,VertexId,EdgeLabel,Boolean)]=res_edges.map(s=>(s._1,s._2,s._3,true))
    //      val first_new_num=newedges.length
    //      val max_loop=max_complete_loop_turn
    //      var turn=0
    //      while(continue){
    //        println("start loop ")
    //        tmp_str+="start loop "
    //        val t0=System.nanoTime():Double
    //        turn+=1
    //        val m_a_l=(oldedges ++ newedges).flatMap(s=>List((s._1,((s._1,s._2),s._3,s._4)),(s._2,((s._1,s._2),s._3,s._4)))
    //        ).groupBy(_._1).toList.map(s=>(s._1,s._2.map(x=>x._2)))
    //        val edges_before=(oldedges ++ newedges).map(s=>(s._1,s._2,s._3))
    //        val (tmp_edges,tmp_str_inloop)=join(m_a_l,grammar,directadd)
    //        tmp_str+=tmp_str_inloop
    //        //      tmp_str+="bfore filter: "+tmp.length
    //        oldedges=oldedges ++newedges.map(s=>(s._1,s._2,s._3,false))
    //        //过滤新边，只保留与本partition有关的新边
    //        newedges=tmp_edges.filter(s=>(key_Set.contains(s._1)||key_Set.contains(s._2))&&edges_before.contains(s)==false)
    //          .map(s=>(s._1,s._2,s._3,true))
    //        continue= (turn<max_loop && !newedges.isEmpty && oldedges.length-first_new_num<max_delta)
    //        val t1=System.nanoTime():Double
    //        println("complete_loop take time: "+((t1-t0)/ 1000000000.0/60).formatted("%.3f") + " min")
    //        if(continue==false){
    //          println("end loop")
    //          tmp_str+="end loop"
    //          recording:+=tmp_str
    //          res_edges=oldedges.map(s=>(s._1,s._2,s._3))
    //          println("after complete loop, res_edges= "+res_edges.length)
    //          recording:+="after complete loop, res_edges= "+res_edges.length
    //        }
    //      }
    //    }
    /**
      * 多线程开启
      */
    //    val executors = Executors.newCachedThreadPool()
    //    val thread = new MyThread
    //    class MyThread extends Thread{
    //      override def run(): Unit = {
    //
    //      }
    //    }
    //    executors.submit(thread)
    /**
      * Hbase过滤
      */
    t0=System.nanoTime():Double
    val len=res_edges.length
    res_edges= {
      val res=HBase_OP.queryHbase_inPartition(res_edges,nodes_num_bitsize,symbol_num_bitsize,htable_name,
        htable_split_Map,
        htable_nodes_interval,
        Hbase_interval,default_split)
      //      recording:+="res_edges confirmed new by Hbase: "+res.length
      res
    }
    t1=System.nanoTime():Double
    println("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    recording:+=("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    List((res_edges,recording,coarest_num)).toIterator
  }

  /**
    * Join操作，输入包含自定义分区
    * @return
    */
  def computeInPartition_completely_UDFP(step:Int,index:Int,
                                      mid_adj:Iterator[(Int,(VertexId,(List[((VertexId,VertexId),EdgeLabel)],List[(
                                        (VertexId,VertexId),EdgeLabel)])))],
                                      grammar:List[((EdgeLabel,EdgeLabel),EdgeLabel)],
                                      htable_name:String,
                                      nodes_num_bitsize:Int,symbol_num_bitsize:Int,
                                      directadd:Map[EdgeLabel,EdgeLabel],
                                      is_complete_loop:Boolean,max_complete_loop_turn:Int,max_delta:Int,
                                      htable_split_Map:Map[Int,String],
                                      htable_nodes_interval:Int,
                                      Hbase_interval:Int,
                                      default_split:String)
  :Iterator[(List[(VertexId,VertexId,EdgeLabel)],List[String],Long)]={

    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var recording:List[String]=List()
    val mid_adj_list=mid_adj.toList.map(s=>s._2)
    //    var old_edges:List[(VertexId,VertexId,EdgeLabel)]=mid_adj_list.flatMap(s=>(s._2)).map(s=>(s._1._1,s._1._2,s._2))
    println("At STEP "+step+", partition "+index)
    recording:+="At STEP "+step+", partition "+index
    var (res_edges,tmp_str,coarest_num)=join_2(mid_adj_list,grammar,directadd)
    recording:+=tmp_str
    //    res_edges=res_edges.filter(s=> !old_edges.contains(s))
    //    println("res_edges distinct: "+res_edges.length)
    //    recording:+="res_edges distinct: "+res_edges.length
    /**
      * form clousure
      * only focused on edges from key inpartition or to key inpartition
      */
    //    if(is_complete_loop){
    //      val key_Set=mid_adj_list.map(s=>s._1).toSet
    //      var continue:Boolean=is_complete_loop
    //      var oldedges:List[(VertexId,VertexId,EdgeLabel,Boolean)]=old_edges.map(s=>(s._1,s._2,s._3,false))
    //      var newedges:List[(VertexId,VertexId,EdgeLabel,Boolean)]=res_edges.map(s=>(s._1,s._2,s._3,true))
    //      val first_new_num=newedges.length
    //      val max_loop=max_complete_loop_turn
    //      var turn=0
    //      while(continue){
    //        println("start loop ")
    //        tmp_str+="start loop "
    //        val t0=System.nanoTime():Double
    //        turn+=1
    //        val m_a_l=(oldedges ++ newedges).flatMap(s=>List((s._1,((s._1,s._2),s._3,s._4)),(s._2,((s._1,s._2),s._3,s._4)))
    //        ).groupBy(_._1).toList.map(s=>(s._1,s._2.map(x=>x._2)))
    //        val edges_before=(oldedges ++ newedges).map(s=>(s._1,s._2,s._3))
    //        val (tmp_edges,tmp_str_inloop)=join(m_a_l,grammar,directadd)
    //        tmp_str+=tmp_str_inloop
    //        //      tmp_str+="bfore filter: "+tmp.length
    //        oldedges=oldedges ++newedges.map(s=>(s._1,s._2,s._3,false))
    //        //过滤新边，只保留与本partition有关的新边
    //        newedges=tmp_edges.filter(s=>(key_Set.contains(s._1)||key_Set.contains(s._2))&&edges_before.contains(s)==false)
    //          .map(s=>(s._1,s._2,s._3,true))
    //        continue= (turn<max_loop && !newedges.isEmpty && oldedges.length-first_new_num<max_delta)
    //        val t1=System.nanoTime():Double
    //        println("complete_loop take time: "+((t1-t0)/ 1000000000.0/60).formatted("%.3f") + " min")
    //        if(continue==false){
    //          println("end loop")
    //          tmp_str+="end loop"
    //          recording:+=tmp_str
    //          res_edges=oldedges.map(s=>(s._1,s._2,s._3))
    //          println("after complete loop, res_edges= "+res_edges.length)
    //          recording:+="after complete loop, res_edges= "+res_edges.length
    //        }
    //      }
    //    }
    /**
      * 多线程开启
      */
    //    val executors = Executors.newCachedThreadPool()
    //    val thread = new MyThread
    //    class MyThread extends Thread{
    //      override def run(): Unit = {
    //
    //      }
    //    }
    //    executors.submit(thread)
    /**
      * Hbase过滤
      */
    t0=System.nanoTime():Double
    val len=res_edges.length
    res_edges= {
      val res=HBase_OP.queryHbase_inPartition(res_edges,nodes_num_bitsize,symbol_num_bitsize,htable_name,
        htable_split_Map,
        htable_nodes_interval,
        Hbase_interval,default_split)
      //      recording:+="res_edges confirmed new by Hbase: "+res.length
      res
    }
    t1=System.nanoTime():Double
    println("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    recording:+=("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    List((res_edges,recording,coarest_num)).toIterator
  }

  /**
    * Node Balance
    * @param node_size
    * @param Max_Par_Size
    * @param defaultpar
    * @return
    */
  def arrangePartition(node_size:RDD[(VertexId,Long)],Max_Par_Size:Long,defaultpar:Int): RDD[(VertexId,Int)] ={
    val index_subindex_node:RDD[((Int,Int),VertexId)]=node_size.partitionBy(new RangePartitioner(defaultpar,node_size))
      .mapPartitionsWithIndex((index,s)=>{
      val list=s.toList
      var res:List[((Int,Int),VertexId)]=List()
      var sum:Long=0L
      var subindex:Int=0
      for(i<-list){
        if(sum+i._2>Max_Par_Size){
          subindex +=1
          sum=i._2
        }
        res :+= ((index,subindex),i._1)
      }
      res.toIterator
    })
    index_subindex_node.groupByKey().zipWithIndex().flatMap(s=>s._1._2.map(x=>(x,s._2.toInt)))
  }

  /**
    * java int[]
    */
  def merge(newedges:Array[(Int,Array[Int])],oldedges:Array[(Int,((Array[Array[Int]],Array[Array[Int]]),(Array[Array[Int]],Array[Array[Int]])))])
  :Array[(Int,((Array[Array[Int]],Array[Array[Int]]), (Array[Array[Int]], Array[Array[Int]])))]={
    var res:ArrayBuffer[(Int,((Array[Array[Int]],Array[Array[Int]]), (Array[Array[Int]], Array[Array[Int]])))
      ]=ArrayBuffer()
    var index_new=0
    var index_old=0
    while(index_new<newedges.length){
      val start=index_new
      val flag=newedges(start)._1
      var new_ele:(ArrayBuffer[Array[Int]],ArrayBuffer[Array[Int]])=(ArrayBuffer(),ArrayBuffer())
      while(index_new<newedges.length&&newedges(index_new)._1==flag){
        if(newedges(index_new)._2(1)==flag) new_ele._1 += Array(newedges(index_new)._2(2),newedges(index_new)._2(0))
        if(newedges(index_new)._2(0)==flag) new_ele._2 += Array(newedges(index_new)._2(2),newedges(index_new)._2(1))
        index_new+=1
      }
      while(index_old<oldedges.length&&oldedges(index_old)._1<flag){
        res += ((oldedges(index_old)._1,(oldedges(index_old)._2._1,(Array[Array[Int]](),Array[Array[Int]]()))))
        index_old+=1
      }
      if(index_old<oldedges.length&&oldedges(index_old)._1==flag){
        res += ((flag,(oldedges(index_old)._2._1,(new_ele._1.toArray,new_ele._2.toArray))))
        index_old+=1
      }
      else{
        res +=((flag,((Array[Array[Int]](),Array[Array[Int]]()),(new_ele._1.toArray,new_ele._2.toArray))))
      }
    }
    if(index_old<oldedges.length){
      res = res ++ oldedges.slice(index_old,oldedges.length)
    }
    res.toArray
  }
  def computeInPartition_completely_flat_java_Array(step:Int,index:Int,
                                              mid_adj:Iterator[(VertexId,((Array[Array[Int]],Array[Array[Int]]),
                                                (Array[Array[Int]],Array[Array[Int]])))],
                                              symbol_num:Int,
                                              grammar:List[((EdgeLabel,EdgeLabel),EdgeLabel)],
                                              nodes_num_bitsize:Int,symbol_num_bitsize:Int,
                                              directadd:Map[EdgeLabel,EdgeLabel],
                                              is_complete_loop:Boolean,max_complete_loop_turn:Int,max_delta:Int,
                                                    useHBase:Boolean,
                                              Batch_QueryHbase:Boolean,
                                              htable_name:String,
                                              htable_split_Map:Map[Int,String],
                                              htable_nodes_interval:Int,
                                              queryHbase_interval:Int,
                                              default_split:String)
  :Iterator[(Int,(Array[Array[Int]],String,Long))]={
    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var recording=""
    var res_edges_array=Array[Array[Int]]()
    var coarest_num=0L
    if(is_complete_loop==false){
      mid_adj.foreach(s=>{
        val res=Graspan_OP_java.join_flat(s._1,
          s._2._1._1//.toArray.map(x=>x.toArray)
          ,s._2._1._2//.toArray.map(x=>x.toArray)
          ,s._2._2._1//.toArray.map(x=>x.toArray)
          ,s._2._2._2//.toArray.map(x=>x.toArray)
          ,grammar.toArray.map(x=>Array(x._1._1,x._1._2,x._2)),symbol_num)
        //      coarest_num += res.length
        //      recording :+="*******************************"
        //      recording :+="mid: "+s._1+"\n"
        //      recording :+="old: "+s._2._1.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
        //      recording :+="new: "+s._2._2.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
        //      recording :+="res: "+res.toList.map(x=>"("+"("+x(0)+","+x(1)+"),"+x(2)+")").mkString(", ")+"\n"
        //      recording :+="*******************************"
        coarest_num +=res.size()
        res_edges_array ++=res
      })

      //    var old_edges:List[(VertexId,VertexId,EdgeLabel)]=mid_adj_list.flatMap(s=>(s._2)).map(s=>(s._1._1,s._1._2,s._2))
      println("At STEP "+step+", partition "+index)
      recording +="At STEP "+step+", partition "+index

      val add_edges=res_edges_array.filter(s=>directadd.contains(s(2))).map(s=>(Array(s(0),s(1),directadd
        .getOrElse(s(2),-1))))
      res_edges_array=(res_edges_array ++ add_edges)
      t1=System.nanoTime():Double
      val toolong={
        if((t1-t0) /1000000000.0<10) "normal"
        else if((t1-t0) /1000000000.0 <100) "longer than 10"
        else "longer than 100"
      }
      println()
      println("||"
        +" origin_formedges: "+coarest_num
        +",\tadd_newedges: "+add_edges.length
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
      recording +=("|| "
        +"origin_formedges: "+coarest_num
        +",\tadd_newedges: "+add_edges.length
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", REPARJOIN"+((t1-t0) /1000000000.0)+"REPARJOIN secs")
    }
    else {
      val mid_adj_Array = mid_adj.toArray
      mid_adj_Array.foreach(s => {
        val res = Graspan_OP_java.join_flat(s._1,
          s._2._1._1 //.toArray.map(x=>x.toArray)
          , s._2._1._2 //.toArray.map(x=>x.toArray)
          , s._2._2._1 //.toArray.map(x=>x.toArray)
          , s._2._2._2 //.toArray.map(x=>x.toArray)
          , grammar.toArray.map(x => Array(x._1._1, x._1._2, x._2)), symbol_num)
        //      coarest_num += res.length
        //      recording :+="*******************************"
        //      recording :+="mid: "+s._1+"\n"
        //      recording :+="old: "+s._2._1.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
        //      recording :+="new: "+s._2._2.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
        //      recording :+="res: "+res.toList.map(x=>"("+"("+x(0)+","+x(1)+"),"+x(2)+")").mkString(", ")+"\n"
        //      recording :+="*******************************"
        coarest_num += res.size()
        res_edges_array ++= res
      })

      //    var old_edges:List[(VertexId,VertexId,EdgeLabel)]=mid_adj_list.flatMap(s=>(s._2)).map(s=>(s._1._1,s._1._2,s._2))
      println("At STEP " + step + ", partition " + index)
      recording += "At STEP " + step + ", partition " + index

      val add_edges = res_edges_array.filter(s => directadd.contains(s(2))).map(s => (Array(s(0), s(1), directadd
        .getOrElse(s(2), -1))))
      res_edges_array = (res_edges_array ++ add_edges)
      /**
        * form clousure
        * only focused on edges from key inpartition or to key inpartition
        */
        println("start closure")
        res_edges_array = res_edges_array.map(_.toVector).distinct.map(_.toArray)
        var oldedges_sort = mid_adj_Array.map(s => (s._1, ((s._2._1._1 ++ s._2._2._1, s._2._1._2 ++ s._2._2._2),
          (Array[Array[Int]](), Array[Array[Int]]())))).sortWith(_._1 < _._1)
        var newedges_sort: Array[(Int, Array[Int])] = res_edges_array
          .flatMap(s => {
            if (s(0) == s(1)) Array((s(0), s))
            else Array((s(0), s), (s(1), s))
          }).sortWith(_._1 < _._1)
        var i = 0
        var continue = true
        while (i < max_complete_loop_turn && continue) {
          i += 1
          oldedges_sort = merge(newedges_sort, oldedges_sort)
          //            println("In closure nodes "+oldedges_sort.length)
          //            println("In closure old f "+oldedges_sort.map(s=>s._2._1._1.length).sum)
          //            println("In closure old b "+oldedges_sort.map(s=>s._2._1._2.length).sum)
          //            println("In closure new f "+oldedges_sort.map(s=>s._2._2._1.length).sum)
          //            println("In closure new b "+oldedges_sort.map(s=>s._2._2._2.length).sum)
          var res_edges_mid = Array[Array[Int]]()
          var coarest_num = 0L
          oldedges_sort.foreach(s => {
            val res = Graspan_OP_java.join_flat(s._1,
              s._2._1._1 //.toArray.map(x=>x.toArray)
              , s._2._1._2 //.toArray.map(x=>x.toArray)
              , s._2._2._1 //.toArray.map(x=>x.toArray)
              , s._2._2._2 //.toArray.map(x=>x.toArray)
              , grammar.toArray.map(x => Array(x._1._1, x._1._2, x._2)), symbol_num)
            //      coarest_num += res.length
            //      recording :+="*******************************"
            //      recording :+="mid: "+s._1+"\n"
            //      recording :+="old: "+s._2._1.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
            //      recording :+="new: "+s._2._2.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
            //      recording :+="res: "+res.toList.map(x=>"("+"("+x(0)+","+x(1)+"),"+x(2)+")").mkString(", ")+"\n"
            //      recording :+="*******************************"
            coarest_num += res.size()
            res_edges_mid ++= res
          })
          res_edges_mid = res_edges_mid.map(_.toVector).distinct.map(_.toArray)
          val add_edges_mid = res_edges_mid.filter(s => directadd.contains(s(2))).map(s => (Array(s(0), s(1),
            directadd
            .getOrElse(s(2), -1))))
          res_edges_mid = (res_edges_mid ++ add_edges_mid)
          coarest_num += res_edges_mid.length
          println("closure produce " + res_edges_mid.length)
          res_edges_array = res_edges_array ++ res_edges_mid
          oldedges_sort = oldedges_sort.map(s => (s._1, ((s._2._1._1 ++ s._2._2._1, s._2._1._2 ++ s._2._2._2),
            (Array[Array[Int]](), Array[Array[Int]]())))).sortWith(_._1 < _._1)
          newedges_sort = res_edges_mid.flatMap(s => {
            if (s(0) == s(1)) Array((s(0), s))
            else Array((s(0), s), (s(1), s))
          }).sortWith(_._1 < _._1)
        }
      t1 = System.nanoTime(): Double
      val toolong = {
        if ((t1 - t0) / 1000000000.0 < 10) "normal"
        else if ((t1 - t0) / 1000000000.0 < 100) "longer than 10"
        else "longer than 100"
      }
      println()
      println("||"
        + " origin_formedges: " + coarest_num
        + ",\tadd_newedges: " + add_edges.length
        + ",\tdistinct newedges: " + res_edges_array.length + " ||"
        + "join take time: " + toolong + ", " + ((t1 - t0) / 1000000000.0) + " secs")
      recording += ("|| "
        + "origin_formedges: " + coarest_num
        + ",\tadd_newedges: " + add_edges.length
        + ",\tdistinct newedges: " + res_edges_array.length + " ||"
        + "join take time: " + toolong + ", REPARJOIN" + ((t1 - t0) / 1000000000.0) + "REPARJOIN secs")
    }
    /**
      * 多线程开启
      */
    //    val executors = Executors.newCachedThreadPool()
    //    val thread = new MyThread
    //    class MyThread extends Thread{
    //      override def run(): Unit = {
    //
    //      }
    //    }
    //    executors.submit(thread)
    /**
      * Hbase过滤
      */
    t0=System.nanoTime():Double
    val len=res_edges_array.length
    val res_edges= {
      if(useHBase)
        HBase_OP.queryHbase_inPartition_java_flat(res_edges_array,nodes_num_bitsize,
        symbol_num_bitsize,
        Batch_QueryHbase,
        htable_name,
        htable_split_Map,
        htable_nodes_interval,
        queryHbase_interval,default_split)
      else res_edges_array
    }
    t1=System.nanoTime():Double
    println("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    recording +=("Query Hbase for edges: \t"+len
      +",\ttake time: \tREPARHBASE"+((t1-t0)/ 1000000000.0).formatted("%.3f") + "REPARHBASE sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    List((index,(res_edges,recording,coarest_num))).toIterator
//    List((res_edges_array.toList,recording,coarest_num)).toIterator
  }

  /**
    * fully compressed
    */
  def computeInPartition_fully_compressed(step:Int,index:Int,
                                                    mid_adj:Iterator[(VertexId,(Array[Int],Int,Int,Int))],
                                                    symbol_num:Int,
                                                    grammar:List[((EdgeLabel,EdgeLabel),EdgeLabel)],
                                                    nodes_num_bitsize:Int,symbol_num_bitsize:Int,
                                                    directadd:Map[EdgeLabel,EdgeLabel],
                                                    is_complete_loop:Boolean,max_complete_loop_turn:Int,max_delta:Int,
                                                    Batch_QueryHbase:Boolean,
                                                    htable_name:String,
                                                    htable_split_Map:Map[Int,String],
                                                    htable_nodes_interval:Int,
                                                    queryHbase_interval:Int,
                                                    default_split:String)
  :Iterator[(Int,(Array[Array[Int]],String,Long))]={
    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var recording=""
    var res_edges_array=Array[Array[Int]]()
    var coarest_num=0L
    if(is_complete_loop==false){
      mid_adj.foreach(s=>{
        val res=Graspan_OP_java.join_fully_compressed(s._1,
          s._2._1,
          s._2._2,
          s._2._3,
          s._2._4
          ,grammar.toArray.map(x=>Array(x._1._1,x._1._2,x._2)),symbol_num)
        //      coarest_num += res.length
        //      recording :+="*******************************"
        //      recording :+="mid: "+s._1+"\n"
        //      recording :+="old: "+s._2._1.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
        //      recording :+="new: "+s._2._2.map(x=>"("+"("+x._1+"),"+x._2+")").mkString(", ")+"\n"
        //      recording :+="res: "+res.toList.map(x=>"("+"("+x(0)+","+x(1)+"),"+x(2)+")").mkString(", ")+"\n"
        //      recording :+="*******************************"
//        coarest_num +=res.size()
        res_edges_array ++=res
      })

      //    var old_edges:List[(VertexId,VertexId,EdgeLabel)]=mid_adj_list.flatMap(s=>(s._2)).map(s=>(s._1._1,s._1._2,s._2))
      println("At STEP "+step+", partition "+index)
      recording +="At STEP "+step+", partition "+index

      val add_edges=res_edges_array.filter(s=>directadd.contains(s(2))).map(s=>(Array(s(0),s(1),directadd
        .getOrElse(s(2),-1))))
      res_edges_array=(res_edges_array ++ add_edges)
      t1=System.nanoTime():Double
      val toolong={
        if((t1-t0) /1000000000.0<10) "normal"
        else if((t1-t0) /1000000000.0 <100) "longer than 10"
        else "longer than 100"
      }
      println()
      println("||"
        +" origin_formedges: "+coarest_num
        +",\tadd_newedges: "+add_edges.length
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
      recording +=("|| "
        +"origin_formedges: "+coarest_num
        +",\tadd_newedges: "+add_edges.length
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", REPARJOIN"+((t1-t0) /1000000000.0)+"REPARJOIN secs")
    }
    else{

    }
    coarest_num=res_edges_array.length
    /**
      * 多线程开启
      */
    //    val executors = Executors.newCachedThreadPool()
    //    val thread = new MyThread
    //    class MyThread extends Thread{
    //      override def run(): Unit = {
    //
    //      }
    //    }
    //    executors.submit(thread)
    /**
      * Hbase过滤
      */
    t0=System.nanoTime():Double
    val len=res_edges_array.length
//    if(res_edges_array.filter(s=>s(0)==s(1)&&s(2)==7).length>0) recording += "Target Exist!"
    val res_edges=
        HBase_OP.queryHbase_inPartition_java_flat(res_edges_array,nodes_num_bitsize,
          symbol_num_bitsize,
          Batch_QueryHbase,
          htable_name,
          htable_split_Map,
          htable_nodes_interval,
          queryHbase_interval,default_split)

//    if(res_edges_array.filter(s=>s(0)==s(1)&&s(2)==7).length>0) recording += "After HBAse Filter Target Exist!"
    t1=System.nanoTime():Double
    println("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    recording +=("Query Hbase for edges: \t"+len
      +",\ttake time: \tREPARHBASE"+((t1-t0)/ 1000000000.0).formatted("%.3f") + "REPARHBASE sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    List((index,(res_edges,recording,coarest_num))).toIterator
    //    List((res_edges_array.toList,recording,coarest_num)).toIterator
  }

  def computeInPartition_fully_compressed_df(step:Int,index:Int,
                                          mid_adj:Iterator[(VertexId,Array[Int])],
                                             e_edges:Array[(Int,Array[Int])],
                                          nodes_num_bitsize:Int,symbol_num_bitsize:Int,
                                          is_complete_loop:Boolean,max_complete_loop_turn:Int,max_delta:Int,
                                          Batch_QueryHbase:Boolean,
                                          htable_name:String,
                                          htable_split_Map:Map[Int,String],
                                          htable_nodes_interval:Int,
                                          queryHbase_interval:Int,
                                          default_split:String)
  :Iterator[(Int,(Array[Array[Int]],String,Long))]={
    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var recording=""
    println("At STEP " + step + ", partition " + index)
    recording += "At STEP " + step + ", partition " + index
    println("get e: "+e_edges.length)
    var res_edges_array=Array[Array[Int]]()
    var coarest_num=0L
    val n=mid_adj.toArray.sortBy(_._1)
    var index_e=0
    var index_n=0
    val len_e=e_edges.length
    var len_n=n.length
    while(index_e<len_e&&index_n<len_n){
      if(e_edges(index_e)._1==n(index_n)._1){
        val res = Graspan_OP_java.join_fully_compressed_df(e_edges(index_e)._1,n(index_n)._2,e_edges(index_e)._2)
        res_edges_array ++= res
        index_e+=1
        index_n+=1
      }
      else if(e_edges(index_e)._1>n(index_n)._1){
        while(index_n<len_n && e_edges(index_e)._1>n(index_n)._1) index_n+=1
      }
      else{
        while(index_e<len_e && e_edges(index_e)._1<n(index_n)._1) index_e+=1
      }
    }
    coarest_num=res_edges_array.size
    if(is_complete_loop==false){
      t1=System.nanoTime():Double
      val toolong={
        if((t1-t0) /1000000000.0<10) "normal"
        else if((t1-t0) /1000000000.0 <100) "longer than 10"
        else "longer than 100"
      }
      println()
      println("||"
        +" origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
      recording +=("|| "
        +"origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", REPARJOIN"+((t1-t0) /1000000000.0)+"REPARJOIN secs")
    }
    else{
      index_e=0
      index_n=0
      var new_n=res_edges_array.map(_.toVector).distinct.sortBy(_(1))
      res_edges_array=new_n.map(_.toArray)
      len_n=new_n.length
      var turn=0
      while(turn<max_complete_loop_turn){
        var res_mid:Array[Vector[Int]]=Array()
        while(index_e<len_e && index_n<len_n){
          if(e_edges(index_e)._1==new_n(index_n)(1)){
            val f=new_n(index_n)(0)
            res_mid =res_mid ++ e_edges(index_e)._2.map(s=>Vector(f,s,0))
            if(index_n==len_n-1||new_n(index_n+1)(1)!=e_edges(index_e)._1){
              index_n+=1
              index_e+=1
            }
            else{
              index_n+=1
            }
          }
          else if(e_edges(index_e)._1>new_n(index_n)(1)){
            while(index_n<len_n && e_edges(index_e)._1>new_n(index_n)(1)) index_n+=1
          }
          else{
            while(index_e<len_e && e_edges(index_e)._1<new_n(index_n)(1)) index_e+=1
          }
        }
        new_n=res_mid.distinct.sortBy(_(1))
//        println("turn: "+turn+", find new edges"+new_n.length)
//        println("is all in res_edges_array ? "+new_n.toSet.subsetOf(res_edges_array.map(_.toVector).toSet))
        res_edges_array=res_edges_array ++ new_n.map(_.toArray)
        len_n=new_n.length
        index_e=0
        index_n=0
        turn +=1
      }
      t1=System.nanoTime():Double
      val toolong={
        if((t1-t0) /1000000000.0<10) "normal"
        else if((t1-t0) /1000000000.0 <100) "longer than 10"
        else "longer than 100"
      }
      println()
      println("||"
        +" origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
      recording +=("|| "
        +"origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", REPARJOIN"+((t1-t0) /1000000000.0)+"REPARJOIN secs")
    }
    coarest_num=res_edges_array.length
    /**
      * 多线程开启
      */
    //    val executors = Executors.newCachedThreadPool()
    //    val thread = new MyThread
    //    class MyThread extends Thread{
    //      override def run(): Unit = {
    //
    //      }
    //    }
    //    executors.submit(thread)
    /**
      * Hbase过滤
      */
    t0=System.nanoTime():Double
    val len=res_edges_array.length
    //    if(res_edges_array.filter(s=>s(0)==s(1)&&s(2)==7).length>0) recording += "Target Exist!"
    val res_edges=
    HBase_OP.queryHbase_inPartition_java_flat(res_edges_array,nodes_num_bitsize,
      symbol_num_bitsize,
      Batch_QueryHbase,
      htable_name,
      htable_split_Map,
      htable_nodes_interval,
      queryHbase_interval,default_split)

    //    if(res_edges_array.filter(s=>s(0)==s(1)&&s(2)==7).length>0) recording += "After HBAse Filter Target Exist!"
    t1=System.nanoTime():Double
    println("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    recording +=("Query Hbase for edges: \t"+len
      +",\ttake time: \tREPARHBASE"+((t1-t0)/ 1000000000.0).formatted("%.3f") + "REPARHBASE sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    List((index,(res_edges,recording,coarest_num))).toIterator
    //    List((res_edges_array.toList,recording,coarest_num)).toIterator
  }


  def computeInPartition_fully_compressed_df_Singleton(step:Int,index:Int,
                                             mid_adj:Iterator[(VertexId,Array[Int])], master:String,input_e_nomaster:String,
                                             nodes_num_bitsize:Int,symbol_num_bitsize:Int,
                                             is_complete_loop:Boolean,max_complete_loop_turn:Int,max_delta:Int,
                                             Batch_QueryHbase:Boolean,
                                             htable_name:String,
                                             htable_split_Map:Map[Int,String],
                                             htable_nodes_interval:Int,
                                             queryHbase_interval:Int,
                                             default_split:String)
  :Iterator[(Int,(Array[Array[Int]],String,Long))]={
    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var recording=""
    println("At STEP " + step + ", partition " + index)
    recording += "At STEP " + step + ", partition " + index
    val e_edges=Dataflow_e_formation.get_e(master,input_e_nomaster,index)
    println("get e: "+e_edges.length)
    var res_edges_array=Array[Array[Int]]()
    var coarest_num=0L
    val n=mid_adj.toArray.sortBy(_._1)
    var index_e=0
    var index_n=0
    val len_e=e_edges.length
    var len_n=n.length
    while(index_e<len_e&&index_n<len_n){
      if(e_edges(index_e)._1==n(index_n)._1){
        val res = Graspan_OP_java.join_fully_compressed_df(e_edges(index_e)._1,n(index_n)._2,e_edges(index_e)._2)
        res_edges_array ++= res
        index_e+=1
        index_n+=1
      }
      else if(e_edges(index_e)._1>n(index_n)._1){
        while(index_n<len_n && e_edges(index_e)._1>n(index_n)._1) index_n+=1
      }
      else{
        while(index_e<len_e && e_edges(index_e)._1<n(index_n)._1) index_e+=1
      }
    }
    coarest_num=res_edges_array.size
    if(is_complete_loop==false){
      t1=System.nanoTime():Double
      val toolong={
        if((t1-t0) /1000000000.0<10) "normal"
        else if((t1-t0) /1000000000.0 <100) "longer than 10"
        else "longer than 100"
      }
      println()
      println("||"
        +" origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
      recording +=("|| "
        +"origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", REPARJOIN"+((t1-t0) /1000000000.0)+"REPARJOIN secs")
    }
    else{
      index_e=0
      index_n=0
      var new_n=res_edges_array.map(_.toVector).distinct.sortBy(_(1))
      res_edges_array=new_n.map(_.toArray)
      len_n=new_n.length
      var turn=0
      while(turn<max_complete_loop_turn){
        var res_mid:Array[Vector[Int]]=Array()
        while(index_e<len_e && index_n<len_n){
          if(e_edges(index_e)._1==new_n(index_n)(1)){
            val f=new_n(index_n)(0)
            res_mid =res_mid ++ e_edges(index_e)._2.map(s=>Vector(f,s,0))
            if(index_n==len_n-1||new_n(index_n+1)(1)!=e_edges(index_e)._1){
              index_n+=1
              index_e+=1
            }
            else{
              index_n+=1
            }
          }
          else if(e_edges(index_e)._1>new_n(index_n)(1)){
            while(index_n<len_n && e_edges(index_e)._1>new_n(index_n)(1)) index_n+=1
          }
          else{
            while(index_e<len_e && e_edges(index_e)._1<new_n(index_n)(1)) index_e+=1
          }
        }
        new_n=res_mid.distinct.sortBy(_(1))
        //        println("turn: "+turn+", find new edges"+new_n.length)
        //        println("is all in res_edges_array ? "+new_n.toSet.subsetOf(res_edges_array.map(_.toVector).toSet))
        res_edges_array=res_edges_array ++ new_n.map(_.toArray)
        len_n=new_n.length
        index_e=0
        index_n=0
        turn +=1
      }
      t1=System.nanoTime():Double
      val toolong={
        if((t1-t0) /1000000000.0<10) "normal"
        else if((t1-t0) /1000000000.0 <100) "longer than 10"
        else "longer than 100"
      }
      println()
      println("||"
        +" origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", "+((t1-t0) /1000000000.0)+" secs")
      recording +=("|| "
        +"origin_formedges: "+coarest_num
        +",\tdistinct newedges: " +res_edges_array.length+" ||"
        +"join take time: "+toolong+", REPARJOIN"+((t1-t0) /1000000000.0)+"REPARJOIN secs")
    }
    coarest_num=res_edges_array.length
    /**
      * 多线程开启
      */
    //    val executors = Executors.newCachedThreadPool()
    //    val thread = new MyThread
    //    class MyThread extends Thread{
    //      override def run(): Unit = {
    //
    //      }
    //    }
    //    executors.submit(thread)
    /**
      * Hbase过滤
      */
    t0=System.nanoTime():Double
    val len=res_edges_array.length
    //    if(res_edges_array.filter(s=>s(0)==s(1)&&s(2)==7).length>0) recording += "Target Exist!"
    val res_edges=
    HBase_OP.queryHbase_inPartition_java_flat(res_edges_array,nodes_num_bitsize,
      symbol_num_bitsize,
      Batch_QueryHbase,
      htable_name,
      htable_split_Map,
      htable_nodes_interval,
      queryHbase_interval,default_split)

    //    if(res_edges_array.filter(s=>s(0)==s(1)&&s(2)==7).length>0) recording += "After HBAse Filter Target Exist!"
    t1=System.nanoTime():Double
    println("Query Hbase for edges: \t"+len
      +",\ttake time: \t"+((t1-t0)/ 1000000000.0).formatted("%.3f") + " sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    recording +=("Query Hbase for edges: \t"+len
      +",\ttake time: \tREPARHBASE"+((t1-t0)/ 1000000000.0).formatted("%.3f") + "REPARHBASE sec"
      +", \tres_edges:             \t"+res_edges.length+"\n")
    List((index,(res_edges,recording,coarest_num))).toIterator
    //    List((res_edges_array.toList,recording,coarest_num)).toIterator
  }

}