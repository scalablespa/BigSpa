package utils

import cn.edu.nju.pasalab.db.{BasicKVDatabaseClient, ShardedRedisClusterClient, Utils}

import scala.collection.mutable.ArrayBuffer

/**
  * Created by cycy on 2018/4/13.
  */
object Redis_OP {

  def Array2ByteArray(edge:Array[Int]):Array[Byte]={
    val res=new Array[Byte](12)
    res(0)=((edge(0) >> 24) & 0xFF).toByte
    res(1)=((edge(0) >> 16) & 0xFF).toByte
    res(2)=((edge(0) >> 8) & 0xFF).toByte
    res(3)=(edge(0) & 0xFF).toByte

    res(4)=((edge(1) >> 24) & 0xFF).toByte
    res(5)=((edge(1) >> 16) & 0xFF).toByte
    res(6)=((edge(1) >> 8) & 0xFF).toByte
    res(7)=(edge(1) & 0xFF).toByte

    res(8)=((edge(2) >> 24) & 0xFF).toByte
    res(9)=((edge(2) >> 16) & 0xFF).toByte
    res(10)=((edge(2) >> 8) & 0xFF).toByte
    res(11)=(edge(2) & 0xFF).toByte

    res
  }

  def Triple2String(src:Int,dst:Int,edgelabel:Int):Array[Byte]={
    val res=new Array[Byte](12)
    res(0)=((src >> 24) & 0xFF).toByte
    res(1)=((src >> 16) & 0xFF).toByte
    res(2)=((src >> 8) & 0xFF).toByte
    res(3)=(src & 0xFF).toByte

    res(4)=((dst >> 24) & 0xFF).toByte
    res(5)=((dst >> 16) & 0xFF).toByte
    res(6)=((dst >> 8) & 0xFF).toByte
    res(7)=(dst & 0xFF).toByte

    res(8)=((edgelabel >> 24) & 0xFF).toByte
    res(9)=((edgelabel >> 16) & 0xFF).toByte
    res(10)=((edgelabel >> 8) & 0xFF).toByte
    res(11)=(edgelabel & 0xFF).toByte

    res
  }

  @throws[Exception]
  def updateRedis_inPartition(edges: Iterator[Array[Int]], updateRedis_interval: Int) {
    val items = edges.toArray
    try{
      val client: BasicKVDatabaseClient = ShardedRedisClusterClient.getProcessLevelClient
      var keys: Array[Array[Byte]] = null
      var values: Array[Array[Byte]] = null
      keys = new Array[Array[Byte]](items.size)
      values = new Array[Array[Byte]](items.size)
      var i: Int = 0
      while (i < items.size) {
        {
          keys(i) = Array2ByteArray(items(i))
          values(i) ="1".getBytes()
        }
        {
          i += 1
        }
      }
      Utils.batchInput(client, keys, values, updateRedis_interval)
  }
    catch {
      case e: Exception => {
        e.printStackTrace()
      }
    }
  }

  @throws[Exception]
  def queryRedis_compressed(compressed_edges: Array[Int]): Array[Array[Int]] = {
    val client: BasicKVDatabaseClient = ShardedRedisClusterClient.getProcessLevelClient
    val all_edges_num: Int = compressed_edges.length / 3
    val keys: Array[Array[Byte]] = new Array[Array[Byte]](all_edges_num)
    val res:ArrayBuffer[Array[Int]]=new ArrayBuffer[Array[Int]](all_edges_num)
    var i: Int = 0
    while (i < all_edges_num) {
      {
        keys(i) =Triple2String(compressed_edges(i * 3), compressed_edges(i * 3 + 1), compressed_edges(i * 3 + 2))
      }
      {
        i += 1
      }
    }
    val values: Array[Array[Byte]] = client.getAll(keys)
    i=0
    while (i<all_edges_num){
      if(values(i)==null) res.append(Array(compressed_edges(i*3),compressed_edges(i*3+1),compressed_edges(i*3+2)))
      i+=1
    }
    res.toArray
  }

  @throws[Exception]
  def queryRedis_compressed_df(compressed_edges: Array[Long]):
  Array[Array[Int]] = {
    val client: BasicKVDatabaseClient = ShardedRedisClusterClient.getProcessLevelClient
    val all_edges_num: Int = compressed_edges.length
    val keys: Array[Array[Byte]] = new Array[Array[Byte]](all_edges_num)
    val res:ArrayBuffer[Array[Int]]=new ArrayBuffer[Array[Int]](all_edges_num*3)
    var i: Int = 0
    while (i < all_edges_num) {
        keys(i) =Triple2String((compressed_edges(i) & 0xffffffffL).toInt,(compressed_edges(i)>>>32).toInt, 0)
        i += 1
    }
    val values: Array[Array[Byte]] = client.getAll(keys)
    i=0
    while (i<all_edges_num){
      if(values(i)==null) res.append(Array((compressed_edges(i) & 0xffffffffL).toInt,(compressed_edges(i)>>>32).toInt, 0))
      i+=1
    }
    res.toArray
  }

  def queryRedis(edges: Array[Array[Int]]): Array[Array[Int]] = {
    val client: BasicKVDatabaseClient = ShardedRedisClusterClient.getProcessLevelClient
    val all_edges_num: Int = edges.length
    val keys: Array[Array[Byte]] = new Array[Array[Byte]](all_edges_num)
    var i: Int = 0
    while (i < all_edges_num) {
      {
        keys(i) = Array2ByteArray(edges(i))
      }
      {
        i += 1
      }
    }
    val values: Array[Array[Byte]] = client.getAll(keys)
    (edges zip values).filter(s=>s._2==null).map(s=>s._1)
  }
}
