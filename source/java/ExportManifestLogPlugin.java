package edu.uams.tcia;

import java.io.File;
import java.util.Arrays;
import java.util.Hashtable;
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
 * A Plugin to log entries in the export manifest.
 */
public class ExportManifestLogPlugin extends AbstractPlugin {
	
	static final Logger logger = Logger.getLogger(ExportManifestLogPlugin.class);
	
	Hashtable<String,Entry> manifest = null;
	volatile int startingQuarantineCount = 0;
	volatile int queuedInstanceCount = 0;
	volatile int manifestInstanceCount = 0;
	String tciaPluginID = "";
	
	String[] localColumnNames = {
		"Collection",
		"SiteName",
		"PatientID",
		"De-idPatientID",
		"StudyDate",
		"De-idStudyDate",
		"SeriesInstanceUID",
		"De-idSeriesInstanceUID",
		"StudyDescription",
		"SeriesDescription",
		"Modality",
		"NumFiles"
	};
	
	String[] exportColumnNames = {
		"Collection",
		"SiteName",
		"De-idPatientID",
		"De-idStudyDate",
		"De-idSeriesInstanceUID",
		"StudyDescription",
		"SeriesDescription",
		"Modality",
		"NumFiles"
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
	public ExportManifestLogPlugin(Element element) {
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
	public synchronized void log(DicomObject dob, DicomObject cachedDOB) { 
		String uid = dob.getSeriesInstanceUID();
		Entry entry = manifest.get(uid);
		if (entry == null) entry = new Entry(dob, cachedDOB);
		entry.numFiles++;
		manifest.put(uid, entry);
		manifestInstanceCount++;
	}
	
	/**
	 * Clear the log.
	 */
	public synchronized void clear() {
		manifest.clear();
	}
	
	/**
	 * Initialize the Anonymizer Pipeline counts.
	 */
	public synchronized Document initializeAnonymizerPipelineCounts() throws Exception {
		manifestInstanceCount = getManifestInstanceCount();
		queuedInstanceCount = 0;
		startingQuarantineCount = getAnonymizerPipelineQuarantineCount();
		return getManifestStatus();
	}
	
	public synchronized int getAnonymizerPipelineQuarantineCount() {
		if (tciaPluginID != null) {
			Plugin plugin = Configuration.getInstance().getRegisteredPlugin(tciaPluginID);
			if (plugin instanceof TCIAPlugin) {
				TCIAPlugin tciaPlugin = (TCIAPlugin)plugin;
				DicomAnonymizer da = tciaPlugin.getAnonymizer();
				Pipeline pipe = da.getPipeline();
				int n = 0;
				for (PipelineStage stage : pipe.getStages()) {
					Quarantine quarantine = stage.getQuarantine();
					if (quarantine != null) n += quarantine.getSize();
				}
				return n;
			}
			else logger.warn("Unable to find TCIAPlugin with ID \""+tciaPluginID+"\"");
		}
		return 0;
	}
	
	public synchronized int getManifestInstanceCount() {
		int n = 0;
		for (Entry entry : manifest.values()) {
			n += entry.numFiles;
		}
		return n;
	}
	
	/**
	 * Count a queuedInstance.
	 */
	public synchronized void incrementQueuedInstance() {
		queuedInstanceCount++;
	}
	
	/**
	 * Get the manifest status object.
	 */
	public synchronized Document getManifestStatus() throws Exception {
		Document doc = XmlUtil.getDocument();
		Element root = doc.createElement("Status");
		doc.appendChild(root);
		root.setAttribute("startingQuarantineCount", Integer.toString(startingQuarantineCount));
		root.setAttribute("currentQuarantineCount", Integer.toString(getAnonymizerPipelineQuarantineCount()));
		root.setAttribute("currentManifestInstanceCount", Integer.toString(getManifestInstanceCount()));
		root.setAttribute("queuedInstanceCount", Integer.toString(queuedInstanceCount));
		return doc;		
	}

	/**
	 * Get the log as a CSV string.
	 */
	public synchronized String toCSV(boolean includePHI) {
		StringBuffer sb = new StringBuffer();
		String[] columnNames = (includePHI ? localColumnNames : exportColumnNames);
		for (String name : columnNames) {
			sb.append("\""+name+"\",");
		}				
		sb.append(eol);
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		for (Entry e : eArray) {
			sb.append(e.toCSV(includePHI));
		}
		return sb.toString();
	}
	
	/**
	 * Get the log as an XLSX file.
	 */
	public synchronized byte[] toXLSX(boolean includePHI) throws Exception {
	    Workbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet("TCIA");
		Row row = sheet.createRow((short)0);
		CellStyle style = wb.createCellStyle();
		Font font = wb.createFont();
		font.setBold(true);
		style.setFont(font);
		String[] columnNames = (includePHI ? localColumnNames : exportColumnNames);
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
			nextRow = e.toXLSX(sheet, nextRow, includePHI);
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
	public synchronized Document toXML(boolean includePHI) throws Exception {
		Document doc = XmlUtil.getDocument();
		Element root = doc.createElement("Manifest");
		doc.appendChild(root);
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		for (Entry e : eArray) {
			root.appendChild(e.toXML(doc, includePHI));
		}
		return doc;
	}
	
	class Entry implements Comparable<Entry> {
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
		
		public Entry(DicomObject dob, DicomObject cachedDOB) {
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
		public int toXLSX(Sheet sheet, int rowNumber, boolean includePHI) {
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
		public int compareTo(Entry e) {
			int c = patientID.compareTo(e.patientID);
			if (c != 0) return c;
			c = seriesInstanceUID.compareTo(e.seriesInstanceUID);
			return c;
		}
	}
	
}