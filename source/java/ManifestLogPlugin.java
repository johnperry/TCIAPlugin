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

/**
 * A Plugin to log entries in the export manifest.
 */
public class ManifestLogPlugin extends AbstractPlugin {
	
	static final Logger logger = Logger.getLogger(ManifestLogPlugin.class);
	
	Hashtable<String,Entry> manifest = null;
	int startingQuarantineCount = 0;
	int queuedInstanceCount = 0;
	int manifestInstanceCount = 0;
	String tciaPluginID = "";
	
	String[] columnNames = {
		"Collection",
		"SiteName",
		"PatientID",
		"SOPClassUID",
		"Modality",
		"StudyDate",
		"SeriesDate",
		"StudyDescription",
		"SeriesDescription",
		"StudyInstanceUID",
		"SeriesInstanceUID",
		"NumFiles"
	};
	
	/**
	 * IMPORTANT: When the constructor is called, neither the
	 * pipelines nor the HttpServer have necessarily been
	 * instantiated. Any actions that depend on those objects
	 * must be deferred until the start method is called.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the plugin.
	 */
	public ManifestLogPlugin(Element element) {
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
		String sizeLine = "<tr><td width=\"20%\">Number of series</td><td>"+manifest.size()+"</td></tr>";
		return getStatusHTML(sizeLine);
	}

	/**
	 * Log a DicomObject.
	 */
	public void log(DicomObject dob, DicomObject cachedDOB) { 
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
	public void clear() {
		manifest.clear();
		manifestInstanceCount = 0;
		queuedInstanceCount = 0;
		startingQuarantineCount = getAnonymizerPipelineQuarantineCount();
	}
	
	public int getAnonymizerPipelineQuarantineCount() {
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
		}
		return 0;
	}
	
	public int getManifestInstanceCount() {
		int n = 0;
		for (Entry entry : manifest.values()) {
			n += entry.numFiles;
		}
		return n;
	}
	
	/**
	 * Count a queuedInstance.
	 */
	public void incrementQueuedInstance() {
		queuedInstanceCount++;
	}
	
	/**
	 * Get the manifest status object.
	 */
	public Document getManifestStatus() throws Exception {
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
	public String toCSV(boolean includePHI) {
		StringBuffer sb = new StringBuffer();
		for (String name : columnNames) {
			sb.append("\""+name+"\",");
		}				
		sb.append("\n");
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.values().toArray(eArray);
		Arrays.sort(eArray);
		for (Entry e : eArray) {
			sb.append(e.toCSV(includePHI));
		}
		return sb.toString();
	}
	
	/**
	 * Get the log as an XML Document.
	 */
	public Document toXML(boolean includePHI) throws Exception {
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
		public String sopClassUID;
		public String modality;
		public String studyDate;
		public String seriesDate;
		public String studyDescription;
		public String seriesDescription;
		public String studyInstanceUID;
		public String seriesInstanceUID;
		public int numFiles = 0;
		
		public String phiPatientID = null;
		public String phiStudyDate = null;
		public String phiSeriesDate = null;
		public String phiStudyInstanceUID = null;
		public String phiSeriesInstanceUID = null;
		
		public Entry(DicomObject dob, DicomObject cachedDOB) {
			collection = dob.getElementValue(0x00131010);
			siteName = dob.getElementValue(0x00131012);
			patientID = dob.getPatientID();
			sopClassUID = dob.getSOPClassUID();
			modality = dob.getModality();
			studyDate = dob.getStudyDate();
			seriesDate = dob.getSeriesDate();
			studyDescription = dob.getStudyDescription();
			seriesDescription = dob.getSeriesDescription();
			studyInstanceUID = dob.getStudyInstanceUID();
			seriesInstanceUID = dob.getSeriesInstanceUID();
			if (cachedDOB != null) {
				phiPatientID = cachedDOB.getPatientID();
				phiStudyDate = cachedDOB.getStudyDate();
				phiSeriesDate = cachedDOB.getSeriesDate();
				phiStudyInstanceUID = cachedDOB.getStudyInstanceUID();
				phiSeriesInstanceUID = cachedDOB.getSeriesInstanceUID();
			}
		}
		public String toCSV(boolean includePHI) {
			StringBuffer sb = new StringBuffer();
			sb.append("=(\""+collection+"\"),");
			sb.append("=(\""+siteName+"\"),");
			sb.append("=(\""+patientID+"\"),");
			sb.append("=(\""+sopClassUID+"\"),");
			sb.append("=(\""+modality+"\"),");
			sb.append("=(\""+studyDate+"\"),");
			sb.append("=(\""+seriesDate+"\"),");
			sb.append("=(\""+studyDescription+"\"),");
			sb.append("=(\""+seriesDescription+"\"),");
			sb.append("=(\""+studyInstanceUID+"\"),");
			sb.append("=(\""+seriesInstanceUID+"\"),");
			sb.append("\""+numFiles+"\",");
			sb.append("\n");
			if (includePHI && (phiPatientID != null)) {
				sb.append(",,");
				sb.append("=(\""+phiPatientID+"\"),");
				sb.append(",,");
				sb.append("=(\""+phiStudyDate+"\"),");
				sb.append("=(\""+phiSeriesDate+"\"),");
				sb.append(",,");
				sb.append("=(\""+phiStudyInstanceUID+"\"),");
				sb.append("=(\""+phiSeriesInstanceUID+"\"),");
				sb.append("\n");
				sb.append("\n"); //if 2 lines per series, separate the next series by a blank line
			}
			return sb.toString();
		}
		public Element toXML(Document doc, boolean includePHI) {
			Element series = doc.createElement("Series");
			append(doc, series, "Collection", collection);
			append(doc, series, "SiteName", siteName);
			append(doc, series, "PatientID", patientID, phiPatientID, includePHI);
			append(doc, series, "SOPClassUID", sopClassUID);
			append(doc, series, "Modality", modality);
			append(doc, series, "StudyDate", studyDate, phiStudyDate, includePHI);
			append(doc, series, "SeriesDate", seriesDate, phiSeriesDate, includePHI);
			append(doc, series, "StudyDescription", studyDescription);
			append(doc, series, "SeriesDescription", seriesDescription);
			append(doc, series, "StudyInstanceUID", studyInstanceUID, phiStudyInstanceUID, includePHI);
			append(doc, series, "SeriesInstanceUID", seriesInstanceUID, phiSeriesInstanceUID, includePHI);
			append(doc, series, "NumFiles", Integer.toString(numFiles));
			return series;
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
			c = studyInstanceUID.compareTo(e.studyInstanceUID);
			if (c != 0) return c;
			c = seriesInstanceUID.compareTo(e.seriesInstanceUID);
			return c;
		}
	}
	
}