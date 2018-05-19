package edu.uams.tcia;

import java.io.File;
import java.io.Serializable;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;

import org.rsna.util.StringUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.rsna.ctp.objects.DicomObject;

public class ExportManifestEntry implements Serializable, Comparable<ExportManifestEntry> {
	public String collection;
	public String siteName;
	public String patientID;
	public String studyDate;
	public String studyDescription;
	public String seriesDescription;
	public String seriesInstanceUID;
	public String modality;
	public int numFiles = 0;

	public String phiPatientID = null;
	public String phiStudyDate = null;
	public String phiSeriesInstanceUID = null;

	public long firstExport = 0;
	public long lastExport = 0;
	
	static String eol = "\r\n";

	public ExportManifestEntry(DicomObject dob, DicomObject cachedDOB) {
		collection = dob.getElementValue(0x00131010).trim();
		siteName = dob.getElementValue(0x00131012).trim();
		patientID = dob.getPatientID().trim();
		modality = dob.getModality().trim();
		studyDate = dob.getStudyDate().trim();
		studyDescription = dob.getStudyDescription().trim();
		seriesDescription = dob.getSeriesDescription().trim();
		seriesInstanceUID = dob.getSeriesInstanceUID().trim();
		if (cachedDOB != null) {
			phiPatientID = cachedDOB.getPatientID().trim();
			phiStudyDate = cachedDOB.getStudyDate().trim();
			phiSeriesInstanceUID = cachedDOB.getSeriesInstanceUID().trim();
		}
	}

	public ExportManifestEntry(ExportManifestEntry entry) {
		collection = entry.collection;
		siteName = entry.siteName;
		patientID = entry.patientID;
		studyDate = entry.studyDate;
		studyDescription = entry.studyDescription;
		seriesDescription = entry.seriesDescription;
		seriesInstanceUID = entry.seriesInstanceUID;
		modality = entry.modality;
		numFiles = entry.numFiles;
		phiPatientID = entry.phiPatientID;
		phiStudyDate = entry.phiStudyDate;
		phiSeriesInstanceUID = entry.phiSeriesInstanceUID;
		firstExport = entry.firstExport;
		lastExport = entry.lastExport;			
	}

	public String toCSV(boolean includePHI) {
		StringBuffer sb = new StringBuffer();
		sb.append("=(\""+collection+"\"),");
		sb.append("=(\""+siteName+"\"),");
		if (includePHI) sb.append("=(\""+phiPatientID+"\"),");
		sb.append("=(\""+patientID+"\"),");
		if (includePHI) sb.append("=(\""+phiStudyDate+"\"),");
		sb.append("=(\""+studyDate+"\"),");
		if (includePHI) sb.append("=(\""+phiSeriesInstanceUID+"\"),");
		sb.append("=(\""+seriesInstanceUID+"\"),");
		sb.append("=(\""+studyDescription+"\"),");
		sb.append("=(\""+seriesDescription+"\"),");
		sb.append("=(\""+modality+"\"),");
		sb.append("\""+numFiles+"\",");
		sb.append(eol);
		return sb.toString();
	}
	public Element toXML(Document doc, boolean includePHI) {
		Element series = doc.createElement("Series");
		append(doc, series, "Collection", collection);
		append(doc, series, "SiteName", siteName);
		append(doc, series, "PatientID", patientID, phiPatientID, includePHI);
		append(doc, series, "StudyDate", studyDate, phiStudyDate, includePHI);
		append(doc, series, "SeriesInstanceUID", seriesInstanceUID, phiSeriesInstanceUID, includePHI);
		append(doc, series, "StudyDescription", studyDescription);
		append(doc, series, "SeriesDescription", seriesDescription);
		append(doc, series, "Modality", modality);
		append(doc, series, "NumFiles", Integer.toString(numFiles));
		return series;
	}
	public int toXLSX(Sheet sheet, int rowNumber, boolean includePHI, boolean includeDates) {
		Row row = sheet.createRow((short)rowNumber);
		int cell = 0;
		row.createCell(cell++).setCellValue(collection);
		row.createCell(cell++).setCellValue(siteName);
		if (includePHI) row.createCell(cell++).setCellValue(phiPatientID);
		row.createCell(cell++).setCellValue(patientID);
		if (includePHI) row.createCell(cell++).setCellValue(phiStudyDate);
		row.createCell(cell++).setCellValue(studyDate);
		if (includePHI) row.createCell(cell++).setCellValue(phiSeriesInstanceUID);
		row.createCell(cell++).setCellValue(seriesInstanceUID);
		row.createCell(cell++).setCellValue(studyDescription);
		row.createCell(cell++).setCellValue(seriesDescription);
		row.createCell(cell++).setCellValue(modality);
		row.createCell(cell++).setCellValue(numFiles);
		if (includeDates) {
			row.createCell(cell++).setCellValue(StringUtil.getDate(firstExport, "."));
			row.createCell(cell++).setCellValue(StringUtil.getDate(lastExport, "."));
		}
		return rowNumber + 1;
	}
	private void append(Document doc, Element parent, String elementName, String value) {
		Element e = doc.createElement(elementName);
		e.setAttribute("value", value);
		parent.appendChild(e);
	}
	private void append(Document doc, Element parent, String elementName, String value, String phi, boolean includePHI) {
		Element e = doc.createElement(elementName);
		e.setAttribute("value", value);
		if (includePHI) e.setAttribute("phi", phi);
		parent.appendChild(e);
	}
	public int compareTo(ExportManifestEntry e) {
		int c = patientID.compareTo(e.patientID);
		if (c != 0) return c;
		c = seriesInstanceUID.compareTo(e.seriesInstanceUID);
		return c;
	}
}
