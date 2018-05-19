package edu.uams.tcia;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.LinkedList;

import jdbm.RecordManager;
import jdbm.helper.FastIterator;
import jdbm.helper.Tuple;
import jdbm.helper.TupleBrowser;
import jdbm.htree.HTree;

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
import org.rsna.util.JdbmUtil;
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
	
	Hashtable<String,ExportManifestEntry> manifest = null;
	volatile int startingQuarantineCount = 0;
	volatile int queuedInstanceCount = 0;
	volatile int manifestInstanceCount = 0;
	String tciaPluginID = "";
	
	RecordManager recman = null;
	String historyDBName = "__historyDB";
	public HTree seriesIndex = null;	//SeriesInstanceUID
	
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
	
	String[] historyPHIColumnNames = {
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
		"NumFiles",
		"FirstExport",
		"LastExport"
	};
	
	String[] historyColumnNames = {
		"Collection",
		"SiteName",
		"De-idPatientID",
		"De-idStudyDate",
		"De-idSeriesInstanceUID",
		"StudyDescription",
		"SeriesDescription",
		"Modality",
		"NumFiles",
		"FirstExport",
		"LastExport"
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
		getIndex();
		tciaPluginID = element.getAttribute("tciaPluginID");
		manifest = new Hashtable<String,ExportManifestEntry>(); 
		logger.info(id+" Plugin instantiated");
	}

	//Load the index HTree
	private void getIndex() {
		try {
			File indexFile = new File(root, historyDBName);
			recman			= JdbmUtil.getRecordManager( indexFile.getPath() );
			seriesIndex		= JdbmUtil.getHTree(recman, "seriesUIDIndex");
		}
		catch (Exception ex) {
			recman = null;
			logger.warn("Unable to load the persistent index.");
		}
	}
	
	public void shutdown() {
		close();
		super.shutdown();
	}
	
	private void close() {
		if (recman != null) {
			try {
				recman.commit();
				recman.close();
			}
			catch (Exception ex) {
				logger.debug("Unable to commit and close the database");
			}
		}
	}
	
	public void clearHistory() {
		close();
		File db = new File(root, historyDBName+".db");
		File lg = new File(root, historyDBName+".lg");
		db.delete();
		lg.delete();
		getIndex();
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
		ExportManifestEntry entry = manifest.get(uid);
		if (entry == null) entry = new ExportManifestEntry(dob, cachedDOB);
		entry.numFiles++;
		manifest.put(uid, entry);
		manifestInstanceCount++;
		//logger.info("Added series "+uid+" to the export manifest");
	}
	
	/**
	 * Log an exported DicomObject in the persistent index.
	 * This is only done when files are actually sent to the export pipeline;
	 * that's why this is a separate method from the log method, which is 
	 * called immediately after anonymization.
	 */
	public synchronized void logExportedObject(DicomObject dob) { 
		String sopiuid = dob.getSOPInstanceUID();
		String seriesuid = dob.getSeriesInstanceUID();
		try {
			//Get the entry from the persistent index, if possible
			ExportManifestEntry entry = (ExportManifestEntry)seriesIndex.get(seriesuid);
			//If there is no entry in the persistent index,
			//this must be the first object exported from this
			//series. If so, create a new entry from the one in
			//the manifest.
			if (entry == null) {
				entry = manifest.get(seriesuid);
				if (entry != null) {
					//Clone it so we don't modify the object in the manifest
					entry = new ExportManifestEntry(entry);
					//Initialize the count and set the date
					entry.numFiles = 0;
					entry.firstExport = System.currentTimeMillis();
				}
				else {
					//We are exporting an object that doesn't have
					//an entry in the manifest. This should never happen.
					//Since we don't have the PHI version of the original
					//object, we can't make a complete entry, so we're
					//just going to log the problem.
					logger.warn("Unable to log "+sopiuid+" in the persistent index.");
					logger.warn("...SeriesInstanceUID: "+seriesuid);
					logger.warn("...manifest.size:     "+manifest.size());
					for (String s :  manifest.keySet()) {
						logger.info("...manifest key: "+s);
					}
					return;
				}
			}
			//We now have an entry to put into the persistent index.
			//Update the count and the last export date.
			entry.numFiles++;
			entry.lastExport = System.currentTimeMillis();
			//Now store it in the persistent index
			seriesIndex.put(seriesuid, entry);
			return;
		}
		catch (Exception unable) { 
			logger.warn("Unable to log "+sopiuid+" in the persistent index.",unable);
		}
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
		for (ExportManifestEntry entry : manifest.values()) {
			n += entry.numFiles;
		}
		return n;
	}
	
	/**
	 * Count a queued instance.
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
		ExportManifestEntry[] eArray = new ExportManifestEntry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		for (ExportManifestEntry e : eArray) {
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
		ExportManifestEntry[] eArray = new ExportManifestEntry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		int nextRow = 2;
		for (ExportManifestEntry e : eArray) {
			nextRow = e.toXLSX(sheet, nextRow, includePHI, false); //false = do not include dates
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
		ExportManifestEntry[] eArray = new ExportManifestEntry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		for (ExportManifestEntry e : eArray) {
			root.appendChild(e.toXML(doc, includePHI));
		}
		return doc;
	}
	
	/**
	 * Get the log as an XLSX file.
	 */
	public synchronized byte[] toHistoryXLSX(boolean includePHI) throws Exception {
	    Workbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet("TCIA-History");
		Row row = sheet.createRow((short)0);
		CellStyle style = wb.createCellStyle();
		Font font = wb.createFont();
		font.setBold(true);
		style.setFont(font);
		String[] columnNames = (includePHI ? historyPHIColumnNames : historyColumnNames);
		for (int i=0; i<columnNames.length; i++) {
			Cell cell = row.createCell(i);
			cell.setCellValue(columnNames[i]);
			cell.setCellStyle(style);
		}	
		try {
			LinkedList<ExportManifestEntry> list = new LinkedList<ExportManifestEntry>();
			FastIterator f = seriesIndex.values();
			ExportManifestEntry entry;
			while ( (entry=(ExportManifestEntry)f.next()) != null ) {
				list.add(entry);
			}
			ExportManifestEntry[] eArray = new ExportManifestEntry[list.size()];
			eArray = list.toArray(eArray);
			Arrays.sort(eArray);
			int nextRow = 2;
			for (ExportManifestEntry e : eArray) {
				nextRow = e.toXLSX(sheet, nextRow, includePHI, true); //true = include dates
			}
		}
		catch (Exception unable) { /*just return the titles*/ }
		for (int i=0; i<14; i++) sheet.autoSizeColumn(i);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		wb.write(baos);
		return baos.toByteArray();
	}
	
}