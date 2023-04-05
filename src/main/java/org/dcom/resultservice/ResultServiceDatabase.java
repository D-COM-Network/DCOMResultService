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

import org.dcom.core.services.ComplianceCheckResultIndexItem;
import org.dcom.core.services.ComplianceCheckResultItem;
import java.time.LocalDateTime;
import java.util.List;
/**
* An interface that defines how the result service should communicate with a database implementation
*/
public interface ResultServiceDatabase {

	public List<ComplianceCheckResultIndexItem> getComplianceChecks(String uprn);
	public void updateUPRN(String oldUPRN,String newUPRN);
	public List<ComplianceCheckResultItem> getResults(String uprn, String checkId, LocalDateTime start, LocalDateTime end, String documentFilter, String freeText,int level);
	public void addResults(String uprn, String checkId,List<ComplianceCheckResultItem> results,List<String> conditions);
	
}