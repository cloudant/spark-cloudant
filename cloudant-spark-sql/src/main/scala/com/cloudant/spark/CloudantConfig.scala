/*******************************************************************************
* Copyright (c) 2015 IBM Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/
package com.cloudant.spark

import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.JsUndefined
import java.net.URLEncoder
import com.cloudant.spark.common._
import play.api.libs.json.JsNumber

/*
@author yanglei
Only allow one field pushdown now
as the filter today does not tell how to link the filters out And v.s. Or
*/
@serializable class CloudantConfig(val host: String, val dbName: String,
    val indexName: String = null)
    (implicit val username: String, val password: String,
     val partitions:Int, val maxInPartition: Int, val minInPartition:Int,
     val requestTimeout:Long,val concurrentSave:Int, val bulkSize: Int) {
  
  private lazy val dbUrl = {"https://"+ host+"/"+dbName}

  val pkField = "_id"
  val defaultIndex = "_all_docs" // "_changes" does not work for partition
  val default_filter: String = "*:*"
  val partitionSize: Long = 1000000 //1 Mb

  def getPostUrl(): String ={dbUrl}
  
  def getLastUrl(skip: Int): String = {
    if (skip ==0 ) null
    else s"$dbUrl/$defaultIndex?limit=$skip"
  }
  
  def getLastNum(result: JsValue): JsValue = {result \ "last_seq"}
  
  def getTotalUrl(url: String) = {
    if (url.contains('?')) url+"&limit=1"
    else  url+"?limit=1"
  }

  def getDbname(): String ={
    dbName
  }

  def getDbUrl(): String = {
    dbUrl
  }

  def allowPartition(): Boolean = {indexName==null}
    
  def getOneUrl(): String = { dbUrl+ "/_all_docs?limit=1&include_docs=true"}
  def getOneUrlExcludeDDoc1(): String = { dbUrl+ "/_all_docs?endkey=%22_design/%22&limit=1&include_docs=true"}
  def getOneUrlExcludeDDoc2(): String = { dbUrl+ "/_all_docs?startkey=%22_design0/%22&limit=1&include_docs=true"}
    
  def getRangeUrl(field: String = null, start: Any = null, 
      startInclusive:Boolean = false, end:Any =null, 
      endInclusive: Boolean =false, 
      includeDoc: Boolean = true): (String, Boolean) = {
    val (url:String, pusheddown:Boolean) = calculate(field, start, 
      startInclusive, end, endInclusive)
    if (includeDoc){
      if (url.indexOf('?')>0) (url+"&include_docs=true",pusheddown)
      else (url+"?include_docs=true",pusheddown)
    }else
       (url, pusheddown)
  }
    
  private def calculate(field: String, start: Any, startInclusive: Boolean, 
      end:Any, endInclusive: Boolean): (String, Boolean) = {
    if (field!=null && field.equals(pkField)){
      var condition = ""
      if (start!=null && end!=null && start.equals(end)){
        condition += "?key=%22" + URLEncoder.encode(
          start.toString(),"UTF-8") + "%22"
      }else{
        if (start != null) {
          condition += "?startkey=%22" + URLEncoder.encode(
              start.toString(),"UTF-8") + "%22"
        }
        if (end !=null){
          if (start !=null)
            condition += "&"
          else
            condition += "?"
          condition += "endkey=%22" + URLEncoder.encode(
              end.toString(),"UTF-8") + "%22"
          }
      }
      (dbUrl + "/_all_docs" + condition, true)
    }else if (indexName!=null){ //  push down to indexName
      val condition = calculateCondition(field, start, startInclusive,
          end, endInclusive)
      (dbUrl+"/"+indexName+"?q="+condition, true)
    }else
      (s"$dbUrl/$defaultIndex" ,false)
  }

  def calculateCondition(field: String, min:Any, minInclusive: Boolean=false,
        max: Any, maxInclusive: Boolean = false) : String = {
    if (field!=null && ( min !=null || max!= null)){
      var condition = field+":"
      if (min!=null && max!=null && min.equals(max)){
        condition += min
      }
      else{
        if (minInclusive) condition+="["
        else condition +="{"
        if (min!=null) condition += min
        else condition+="*"
        condition+=" TO "
        if (max !=null) condition += max
        else condition += "*"
        if (maxInclusive) condition+="]"
        else condition +="}"
      }
      URLEncoder.encode(condition,"UTF-8")
    }else
      default_filter
  }

  def getSubSetUrl (url: String, skip: Int, limit: Int)
      (implicit convertSkip:(Int) => String): String ={
    val suffix = {
      if (url.indexOf("_all_docs")>0) "include_docs=true&limit=" + 
        limit + "&skip=" + skip
      else if (url.indexOf("_changes")>0) "include_docs=true&limit=" + 
          limit + "&since=" + convertSkip(skip)
      else "include_docs=true&limit=" + limit // TODO Index query does not support subset query. Should disable Partitioned loading?
    }
    if (url.indexOf('?')>0) url+"&"+suffix
    else url+"?"+suffix
  }
    
  def getTotalRows(result: JsValue): Int = {
    val value = result \ "total_rows"
    value match {
      case s : JsUndefined => 
        (result \ "pending").as[JsNumber].value.intValue() + 1
      case _ =>  value.as[JsNumber].value.intValue()
    }
  }

  def getDataSize(result: JsValue): (Int, Long) = {
    val docCount = result \ "doc_count"
    val docCountInt = docCount match {
      case s : JsUndefined => 0
      case _ =>  docCount.as[JsNumber].value.intValue()
    }
    val dataSize = result \"sizes" \ "external"
    val dataSizeLong = dataSize match {
      case s : JsUndefined => 0
      case _ =>  dataSize.as[JsNumber].value.longValue()
    }
    (docCountInt, dataSizeLong)
  }

  def getRows(result: JsValue): Seq[JsValue] = {
    result \\ "doc"
  }
    
  def getBulkPostUrl(): String = {
    dbUrl + "/_bulk_docs"
  }
    
  def getBulkRows(rows: Array[String]): String = {
    val docs = rows.map { x => Json.parse(x) }
    Json.stringify(Json.obj("docs" -> Json.toJson(docs.toSeq)))
  }
}
