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

package org.dcom.resultservice;

import javax.ws.rs.Path;
import javax.inject.Inject;
import org.dcom.core.servicehelper.ServiceBaseInfo;
import org.dcom.core.servicehelper.UserAuthorisationValidator;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.PATCH;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.PathParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.dcom.core.services.ComplianceCheckResultIndexItem;
import org.dcom.core.services.ComplianceCheckResultItem;
import java.util.HashMap;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import java.io.StringReader;
import org.xml.sax.InputSource;
import java.time.LocalDateTime;
import org.dcom.core.security.ServiceCertificate;
import org.dcom.core.services.RuleEngineService;
import org.dcom.core.security.DCOMBearerToken;
import org.dcom.core.DCOM;
import javax.ws.rs.core.MultivaluedMap;
import com.owlike.genson.Genson;
import java.util.List;
import java.util.Set;


/**
* The implementation of the Result Service Rest API
*/
@Path("/")
public class ResultServiceAPI {


	@Inject
	public ResultServiceDatabase database;

	@Inject
	public UserAuthorisationValidator authenticator;

	@Inject
	public ServiceBaseInfo serviceInfo;
	
	//utility functions
	
	private String successMessageJSON="{\"success\":true}";
	private String successMessageXML="<success>true</success>";
	
	
	//returns the level of detail 
	//level 1 is complete full information
	//level 2 is complete full information - but with no identifiable information about human involvement
	//level 3 is the full results - but with no justifications
	//level 4 is just the top level result 
	
	private int authorize(String token) {
		if (token==null) return -1;
		if ( authenticator.validatePermission(token,"level1")) return 1;
		if ( authenticator.validatePermission(token,"level2")) return 2;
		if ( authenticator.validatePermission(token,"level3")) return 3;
		if ( authenticator.validatePermission(token,"level4")) return 4;
		return -1;
	}
	
	
	private boolean authorizeEditor(String token) {
		if (token==null) return false;
		if ( authenticator.validatePermission(token,"editor")) return true;
		return false;
	}
	
	private boolean authorizeRuleEngine(String token) {
		token=token.replace("Bearer","").trim();
		Set<RuleEngineService> ruleEngines=DCOM.getServiceLookup().getRuleEngines();
		for (RuleEngineService ruleEngine: ruleEngines) {
			ServiceCertificate cert=ruleEngine.getCertificate();
			if (cert.checkTokenValidity(new DCOMBearerToken(token))) return true;	
		}
		return false;
	}
	
	
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response serviceInfoJSON() {
		return  Response.ok(serviceInfo.toJSON()).build();
	}
	
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_XML)
	public Response serviceInfoXML() {
		return  Response.ok(serviceInfo.toXML()).build();
	}
	
	@GET
	@Path("/{building}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response buildingJSON(@PathParam("building") String uprn,@HeaderParam("Authorization") String token) {
			StringBuffer str=new StringBuffer();
			int level=authorize(token);
			if (level==-1) 	return Response.status(401).build();
			List<ComplianceCheckResultIndexItem> complianceChecks=database.getComplianceChecks(uprn);
			str.append("[");
			boolean first=true;
			for (ComplianceCheckResultIndexItem item: complianceChecks) {
				if (first) first=false;
				else str.append(",");
				str.append(item.toJSON());
			}
			str.append("]");
			return  Response.ok(str.toString()).build();
	}
	
	@GET
	@Path("/{building}")
	@Produces(MediaType.APPLICATION_XML)
	public Response buildingXML(@PathParam("building") String uprn,@HeaderParam("Authorization") String token) {
		StringBuffer str=new StringBuffer();
		int level=authorize(token);
		if (level==-1) 	return Response.status(401).build();
		List<ComplianceCheckResultIndexItem> complianceChecks=database.getComplianceChecks(uprn);
		str.append("<ComplianceChecks>");
		for (ComplianceCheckResultIndexItem item: complianceChecks) {
			str.append(item.toXML());
		}
		str.append("</ComplianceChecks>");
		return  Response.ok(str.toString()).build();
	}
	
	@PATCH
	@Path("/{building}/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response updateUPRNL(@PathParam("building") String uprn,String body,@HeaderParam("Authorization") String token) {
		if (!authorizeEditor(token)) 	return Response.status(401).build();
		database.updateUPRN(uprn,body);
		return  Response.ok(successMessageJSON).build();
	}
	
	@GET
	@Path("/{building}/all")
	@Produces(MediaType.APPLICATION_JSON)
	public Response buildingAllJSON(@PathParam("building") String uprn,@HeaderParam("Authorization") String token,@Context UriInfo info) {
			return buildingJSON(uprn,null,token,info);
	}
	
	@GET
	@Path("/{building}/all")
	@Produces(MediaType.APPLICATION_XML)
	public Response buildingAllXML(@PathParam("building") String uprn,@HeaderParam("Authorization") String token,@Context UriInfo info) {
		return buildingXML(uprn,null,token,info);
	}
	
	@GET
	@Path("/{building}/{complianceCheckUID}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response buildingJSON(@PathParam("building") String uprn,@PathParam("complianceCheckUID") String uid,@HeaderParam("Authorization") String token,@Context UriInfo info) {
		List<ComplianceCheckResultItem> results=getResults(uprn,uid,token,info);
		StringBuffer str=new StringBuffer();
		str.append("{ \"results\":[");
		boolean first=true;
		for (ComplianceCheckResultItem item: results) {
			if (first) first=false;
			else str.append(",");
			str.append(item.toJSON());
		}
		str.append("]}");
		return  Response.ok(str.toString()).build();
	}
	
	
	
	@GET
	@Path("/{building}/{complianceCheckUID}")
	@Produces(MediaType.APPLICATION_XML)
	public Response buildingXML(@PathParam("building") String uprn,@PathParam("complianceCheckUID") String uid,@HeaderParam("Authorization") String token,@Context UriInfo info) {
		List<ComplianceCheckResultItem> results=getResults(uprn,uid,token,info);
		StringBuffer str=new StringBuffer();
		str.append("<Results>");
		for (ComplianceCheckResultItem item: results) str.append(item.toXML());
		str.append("</Results>");
		return  Response.ok(str.toString()).build();
	}
	
	private List<ComplianceCheckResultItem> getResults(String uprn,String uid,String token,UriInfo info) {
		MultivaluedMap<String,String> queryParams=info.getQueryParameters();
		String sVal=queryParams.getFirst("start");
		LocalDateTime start=null;
		if (sVal!=null) start= LocalDateTime.parse(sVal);
		String eVal=queryParams.getFirst("end");
		LocalDateTime end=null;
		if (eVal!=null) end=LocalDateTime.parse(eVal);
		String documentFilter=queryParams.getFirst("documentFilter");
		String freeText=queryParams.getFirst("search");
		
		int level=authorize(token);
		List<ComplianceCheckResultItem> results = database.getResults(uprn,uid,start,end,documentFilter,freeText,level);
		return results;
	}

	@PUT
	@Path("/{building}/{complianceCheckUID}")
	@Produces(MediaType.APPLICATION_XML)
	@Consumes(MediaType.APPLICATION_XML)
	public Response updateXML(@HeaderParam("Authorization") String token,@PathParam("building") String uprn,@PathParam("complianceCheckUID") String uid,String body) {
			if (!authorizeRuleEngine(token)) 	return Response.status(401).build();
			try {
					DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
					DocumentBuilder builder = factory.newDocumentBuilder();
					Document document = builder.parse(new InputSource(new StringReader(body)));
					Element root = (Element) document.getDocumentElement();
					NodeList conditionElements =  root.getElementsByTagName("conditions");
					ArrayList<String> conditions=new ArrayList<String>();
					for (int i=0; i < conditionElements.getLength();i++) {
						Element cond=(Element) conditionElements.item(i);
						conditions.add(cond.getTextContent());
					}
			
					database.addResults(uprn,uid,ComplianceCheckResultItem.fromJSONCollection(body),conditions);
					return  Response.ok(successMessageJSON).build();
			} catch (Exception e) {
				e.printStackTrace();
				return Response.status(500).build();
				
			}
	}
	
	@PUT
	@Path("/{building}/{complianceCheckUID}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response updateJSON(@HeaderParam("Authorization") String token,@PathParam("building") String uprn,@PathParam("complianceCheckUID") String uid,String body) {
			if (!authorizeRuleEngine(token)) 	return Response.status(401).build();
			Map<String, Object> data = new Genson().deserialize(body, Map.class);
			ArrayList<Object> conditionsTemp=(ArrayList<Object>)data.get("conditions");
			ArrayList<String> conditions=new ArrayList<String>();
			for (Object o: conditionsTemp) conditions.add((String)o);
			database.addResults(uprn,uid,ComplianceCheckResultItem.fromJSONCollection(body),conditions);
			return  Response.ok(successMessageJSON).build();
	}
}