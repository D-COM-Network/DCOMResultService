/*
Copyright (C) 2022 Cardiff University

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

*/

package org.dcom.resultservice.influxdb;

import org.dcom.resultservice.ResultServiceDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import org.dcom.core.services.ComplianceCheckResultIndexItem;
import org.dcom.core.services.ComplianceCheckResultItem;
import java.time.LocalDateTime;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.BucketsApi;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.WriteApi;
import java.time.ZoneOffset;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import java.time.Instant;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.influxdb.client.QueryApi;
import com.influxdb.client.domain.Organization;
import com.influxdb.client.OrganizationsApi;
import java.util.List;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
* The implementation of ResultServiceData for InfluxDB. This seperation enables the service to be ported to use a new backend databy with just the recreation of this one file.
*/
public class InfluxDBResultServiceDatabase implements ResultServiceDatabase {
	
	private static final Logger LOGGER = LoggerFactory.getLogger( InfluxDBResultServiceDatabase.class );
	private String dbName;
	private InfluxDBClient db;
	private Organization org;

	public InfluxDBResultServiceDatabase(String url,String username,String password,String database) {
    	db = InfluxDBClientFactory.create(url,password.toCharArray());
			dbName=database;
			OrganizationsApi orgApi=db.getOrganizationsApi();
			List<Organization> orgs=orgApi.findOrganizations();
			org=null;
			for (Organization orgThis: orgs) {
				if (orgThis.getName().equals("DCOM"))  org=orgThis;
			}
			if (org==null) org=orgApi.createOrganization("DCOM");
			db = InfluxDBClientFactory.create(url,password.toCharArray(),"DCOM");
				
	}
	
	public List<ComplianceCheckResultIndexItem> getComplianceChecks(String uprn) {
			List<ComplianceCheckResultIndexItem> results=new ArrayList<ComplianceCheckResultIndexItem>();
			BucketsApi api=db.getBucketsApi();
			List<Bucket> buckets=api.findBuckets();
			for (Bucket b: buckets) {
				if (b.getName().startsWith(dbName+"_"+uprn)) {
					String uid=b.getName().replace(dbName+"_"+uprn+"_","");
					QueryApi queryApi = db.getQueryApi();
					StringBuffer query=new StringBuffer();
					query.append("from(bucket:\"").append(b.getName()).append("\")");
					query.append(" |> range(start: -100y)");
					query.append(" |> filter(fn: (r) => r._measurement != \"condition\")");
					query.append(" |> sort(columns:[\"_time\"],desc:true)");
					query.append(" |> limit(n:1)");
					List<FluxTable> tables = queryApi.query(query.toString());
					for (FluxTable fluxTable : tables) {
						List<FluxRecord> records = fluxTable.getRecords();
							 for (FluxRecord fluxRecord : records) {
								 	ComplianceCheckResultIndexItem item=new ComplianceCheckResultIndexItem(fluxRecord.getMeasurement(), uid, fluxRecord.getValue().toString(), LocalDateTime.ofInstant(fluxRecord.getTime(),ZoneId.systemDefault()));
									results.add(item);
							 }
					}
				}
			}
			return results;	
	}
	
	public void updateUPRN(String oldUPRN,String newUPRN) {
		List<Bucket> buckets=db.getBucketsApi().findBuckets();
		for (Bucket b: buckets) {
				if (b.getName().startsWith(dbName+"_"+oldUPRN)) {
							b.setName(b.getName().replace(oldUPRN,newUPRN));
				}
		}
	
	}
	
	
	public List<ComplianceCheckResultItem> getResults(String uprn, String checkId, LocalDateTime start, LocalDateTime end, String documentFilter, String freeText,int level) {
			List<ComplianceCheckResultItem> results=new ArrayList<ComplianceCheckResultItem>();
			Bucket bucket=db.getBucketsApi().findBucketByName(dbName+"_"+uprn+"_"+checkId);
			if (bucket==null) return results;
			QueryApi queryApi = db.getQueryApi();
			StringBuffer query=new StringBuffer();
			query.append("from(bucket:\"").append(bucket.getName()).append("\")");
			if (start!=null && end !=null) {
				query.append(" |> range(start: "+start.toString()+", stop:"+end.toString()+")");
			} else if (start!=null) {
					query.append(" |> range(start: "+start.toString()+")");
			} else {
					query.append(" |> range(start: -100y, stop:26h)"); // this is the maximum in the future something can be given time zone irregularities
			}
			if (documentFilter!=null) {
				query.append(" |> filter(fn: (r) => r._measurement == \"condition\" or r._measurement == \""+documentFilter+"\")");
			}
			if (level==4) {
				query.append(" |> sort(columns:[\"_time\"],desc:true)");
				query.append(" |> limit(n:1)");
			}
			query.append(" |> sort(columns:[\"_time\"],desc:true)");
			List<FluxTable> tables = queryApi.query(query.toString());
			for (FluxTable fluxTable : tables) {
				List<FluxRecord> records = fluxTable.getRecords();
					 for (FluxRecord fluxRecord : records) {
							List<String> reasons;
							List<String> supportingFileData;
							List<String> supportingFileContentType;
							if (level <3 ) {
								Object data=fluxRecord.getValueByKey("reasons");
								if (data!=null) reasons=Arrays.asList(data.toString().split(",")); else reasons=new ArrayList<String>();
								data=fluxRecord.getValueByKey("supportingFileData");
								if (data!=null) supportingFileData=Arrays.asList(data.toString().split(",")); else supportingFileData=new ArrayList<String>();
								data=fluxRecord.getValueByKey("supportingFileContentType");
								if (data!=null) supportingFileContentType=Arrays.asList(data.toString().split(",")); else 	supportingFileContentType=new ArrayList<String>();
							} else {
								reasons=new ArrayList<String>();
								supportingFileData=new ArrayList<String>();
								supportingFileContentType=new ArrayList<String>();
							}
							String attributation="";
							if (level <2) {
								if (fluxRecord.getValueByKey("attributation")!=null) attributation=fluxRecord.getValueByKey("attributation").toString();
							}
							ComplianceCheckResultItem item=new ComplianceCheckResultItem(fluxRecord.getMeasurement(), LocalDateTime.ofInstant(fluxRecord.getTime(),ZoneId.systemDefault()),reasons,attributation,fluxRecord.getValue().toString(),supportingFileData,supportingFileContentType);
							results.add(item);
					 }
			}
			
			
			return results;
	}
	
	public void addResults(String uprn, String checkId,List<ComplianceCheckResultItem> results,List<String> conditions) {
		 	WriteApi writeApi=db.getWriteApi();
			BucketsApi buckets=db.getBucketsApi();
			Bucket bucket=buckets.findBucketByName(dbName+"_"+uprn+"_"+checkId);
			if (bucket==null) {
				 bucket=new Bucket();
				 bucket.setOrgID(org.getId());
				 bucket.setName(dbName+"_"+uprn+"_"+checkId);
				 bucket=buckets.createBucket(bucket);
			}

		
			String conditionString=String.join(",",conditions);
		  Point point = Point.measurement("conditions").addTag("reasons", conditionString);
			point.addField("result","true");
			writeApi.writePoint(dbName+"_"+uprn+"_"+checkId,"DCOM",point);
			
			for (ComplianceCheckResultItem result: results){
				point = Point.measurement(result.getReference());
				point.time(result.getTime().toInstant(ZoneOffset.UTC),WritePrecision.NS);
				if (result.getSupportingFileContentType()!=null && result.getSupportingFileContentType().size()>0) { 
					point = point.addTag("supportingFileContentType",String.join(",",result.getSupportingFileContentType()));
				}
				if (result.getSupportingFileData()!=null && result.getSupportingFileData().size()>0) point = point.addTag("supportingFileData",String.join(",",result.getSupportingFileData()));
				if (result.getResult()!=null) point = point.addField("result",result.getResult());
				if (result.getAttributation()!=null) point = point.addTag("attributation",result.getAttributation());
				
				if (result.getReasons()!=null && result.getReasons().size()>0) point = point.addTag("reasons",String.join(",",result.getReasons()));
				
				writeApi.writePoint(dbName+"_"+uprn+"_"+checkId,"DCOM",point);
			}
			
	}
	

}