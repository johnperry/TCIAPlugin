package edu.uams.tcia;

import java.io.File;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.HashSet;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.pipeline.Pipeline;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.pipeline.Quarantine;
import org.rsna.ctp.plugin.AbstractPlugin;
import org.rsna.ctp.plugin.Plugin;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.rsna.util.XmlUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;

/**
 * A Plugin to log entries in the import manifest.
 */
public class ImportManifestLogPlugin extends AbstractPlugin {
	
	static final Logger logger = Logger.getLogger(ImportManifestLogPlugin.class);
	
	Hashtable<String,Entry> manifest = null;
	String tciaPluginID = "";
	
	String[] columnNames = {
		"PatientID",
		"StudyDate",
		"SeriesInstanceUID",
		"StudyDescription",
		"SeriesDescription",
		"Modality",
		"NumFiles"
	};
	
	String[] templateColumnNames1 = {
		"",
		"ptid",
		"cname",
		"sname",
		"ddate"
	};
	String[] templateColumnNames2 = {
		"Local Patient ID",
		"Anonymized Patient ID",
		"Collection Name",
		"Site Name",
		"Diagnosis Date (M/D/YYYY)"
	};
	
	static String eol = "\r\n";
	
	/**
	 * IMPORTANT: When the constructor is called, neither the
	 * pipelines nor the HttpServer have necessarily been
	 * instantiated. Any actions that depend on those objects
	 * must be deferred until the start method is called.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the plugin.
	 */
	public ImportManifestLogPlugin(Element element) {
		super(element);
		tciaPluginID = element.getAttribute("tciaPluginID");
		manifest = new Hashtable<String,Entry>(); 
		logger.info(id+" Plugin instantiated");
	}

	/**
	 * Get HTML text displaying the current status of the plugin.
	 * @return HTML text displaying the current status of the plugin.
	 */
	public synchronized String getStatusHTML() {
		String seriesLine = "<tr><td width=\"20%\">Number of series</td><td>"+manifest.size()+"</td></tr>";
		String instanceLine = "<tr><td width=\"20%\">Number of instances</td><td>"+getManifestInstanceCount()+"</td></tr>";
		return getStatusHTML(seriesLine + instanceLine);
	}

	/**
	 * Log a DicomObject.
	 */
	public synchronized void log(DicomObject dob) { 
		String uid = dob.getSeriesInstanceUID();
		Entry entry = manifest.get(uid);
		if (entry == null) entry = new Entry(dob);
		entry.numFiles++;
		manifest.put(uid, entry);
	}
	
	/**
	 * Clear the log.
	 */
	public synchronized void clear() {
		manifest.clear();
	}
	
	public int getManifestInstanceCount() {
		int n = 0;
		for (Entry entry : manifest.values()) {
			n += entry.numFiles;
		}
		return n;
	}
	
	/**
	 * Get the lookup table template XLSX file.
	 */
	public byte[] getLookupTableTemplate(String idParam) throws Exception {
	    Workbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet("TCIA");
		Row row = sheet.createRow((short)0);
		for (int i=0; i<templateColumnNames1.length; i++) {
			Cell cell = row.createCell(i);
			cell.setCellValue(templateColumnNames1[i]);
		}
		
		CellStyle style = wb.createCellStyle();
		Font font = wb.createFont();
		font.setBold(true);
		style.setFont(font);
		row = sheet.createRow((short)1);
		for (int i=0; i<templateColumnNames2.length; i++) {
			Cell cell = row.createCell(i);
			cell.setCellValue(templateColumnNames2[i]);
			cell.setCellStyle(style);
		}
		
		HashSet<String> set = new HashSet<String>();
		if ((idParam == null) || idParam.trim().equals(""))
			for (String key : manifest.keySet()) {
				Entry entry = manifest.get(key);
				set.add(entry.patientID);
			}
		else {
			String[] ids = idParam.split("\\|");
			for (String id : ids) {
				set.add(id.trim());
			}
		}
		String[] ptids = new String[set.size()];
		ptids = set.toArray(ptids);
		Arrays.sort(ptids);
		
		int rowNumber = 2;
		for (String ptid : ptids) {
			row = sheet.createRow((short)rowNumber);
			Cell cell = row.createCell(0);
			cell.setCellValue(ptid);
			rowNumber++;
		}
		for (int i=0; i<templateColumnNames2.length; i++) sheet.autoSizeColumn(i);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		wb.write(baos);
		return baos.toByteArray();
	}

	/**
	 * Get the log as a CSV string.
	 */
	public String toCSV() {
		StringBuffer sb = new StringBuffer();
		for (String name : columnNames) {
			sb.append("\""+name+"\",");
		}				
		sb.append(eol);
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		for (Entry e : eArray) {
			sb.append(e.toCSV());
		}
		return sb.toString();
	}
	
	/**
	 * Get the log as an XLSX file.
	 */
	public byte[] toXLSX() throws Exception {
	    Workbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet("TCIA");
		Row row = sheet.createRow((short)0);
		CellStyle style = wb.createCellStyle();
		Font font = wb.createFont();
		font.setBold(true);
		style.setFont(font);
		for (int i=0; i<columnNames.length; i++) {
			Cell cell = row.createCell(i);
			cell.setCellValue(columnNames[i]);
			cell.setCellStyle(style);
		}			
		for (int i=0; i<12; i++) sheet.autoSizeColumn(i);
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		int nextRow = 2;
		for (Entry e : eArray) {
			nextRow = e.toXLSX(sheet, nextRow);
		}
		for (int i=0; i<3; i++) sheet.autoSizeColumn(i);
		for (int i=4; i<9; i++) sheet.autoSizeColumn(i);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		wb.write(baos);
		return baos.toByteArray();
	}
	
	/**
	 * Get the log as an XML Document.
	 */
	public Document toXML() throws Exception {
		Document doc = XmlUtil.getDocument();
		Element root = doc.createElement("Manifest");
		doc.appendChild(root);
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		for (Entry e : eArray) {
			root.appendChild(e.toXML(doc));
		}
		return doc;
	}
	
	class Entry implements Comparable<Entry> {
		public String patientID;
		public String studyDate;
		public String studyDescription;
		public String seriesDescription;
		public String seriesInstanceUID;
		public String modality;
		public int numFiles = 0;
		
		public Entry(DicomObject dob) {
			patientID = dob.getPatientID().trim();
			modality = dob.getModality().trim();
			studyDate = dob.getStudyDate().trim();
			studyDescription = dob.getStudyDescription().trim();
			seriesDescription = dob.getSeriesDescription().trim();
			seriesInstanceUID = dob.getSeriesInstanceUID().trim();
		}
		public String toCSV() {
			StringBuffer sb = new StringBuffer();
			sb.append("=(\""+patientID+"\"),");
			sb.append("=(\""+studyDate+"\"),");
			sb.append("=(\""+seriesInstanceUID+"\"),");
			sb.append("=(\""+studyDescription+"\"),");
			sb.append("=(\""+seriesDescription+"\"),");
			sb.append("=(\""+modality+"\"),");
			sb.append("\""+numFiles+"\",");
			sb.append(eol);
			return sb.toString();
		}
		public Element toXML(Document doc) {
			Element series = doc.createElement("Series");
			append(doc, series, "PatientID", patientID);
			append(doc, series, "StudyDate", studyDate);
			append(doc, series, "SeriesInstanceUID", seriesInstanceUID);
			append(doc, series, "StudyDescription", studyDescription);
			append(doc, series, "SeriesDescription", seriesDescription);
			append(doc, series, "Modality", modality);
			append(doc, series, "NumFiles", Integer.toString(numFiles));
			return series;
		}
		public int toXLSX(Sheet sheet, int rowNumber) {
			Row row = sheet.createRow((short)rowNumber);
			int cell = 0;
			row.createCell(cell++).setCellValue(patientID);
			row.createCell(cell++).setCellValue(studyDate);
			row.createCell(cell++).setCellValue(seriesInstanceUID);
			row.createCell(cell++).setCellValue(studyDescription);
			row.createCell(cell++).setCellValue(seriesDescription);
			row.createCell(cell++).setCellValue(modality);
			row.createCell(cell++).setCellValue(numFiles);
			return rowNumber + 1;
		}
 		private void append(Document doc, Element parent, String elementName, String value) {
			Element e = doc.createElement(elementName);
			e.setAttribute("value", value);
			parent.appendChild(e);
		}
		public int compareTo(Entry e) {
			int c = patientID.compareTo(e.patientID);
			if (c != 0) return c;
			c = seriesInstanceUID.compareTo(e.seriesInstanceUID);
			return c;
		}
	}
	
}