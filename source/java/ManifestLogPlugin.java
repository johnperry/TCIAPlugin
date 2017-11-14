package edu.uams.tcia;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedList;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.plugin.AbstractPlugin;
import org.rsna.ctp.objects.DicomObject;
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
	
	String exportURL;
	String[] manifestElementNames = null;
	LinkedList<Integer> manifestLogTags = null;
	LinkedList<Entry> manifest = null;
	
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
		this.exportURL = element.getAttribute("exportURL").trim();
		manifestElementNames = element.getAttribute("manifestLogTags").split(";");
		manifestLogTags = new LinkedList<Integer>();
		for (String name : manifestElementNames) {
			name = name.trim();
			if (!name.equals("")) {
				int tag = DicomObject.getElementTag(name);
				if (tag != 0) manifestLogTags.add(new Integer(tag));
				else logger.warn(name+": Unknown DICOM element tag: \""+name+"\"");
			}
		}
		manifest = new LinkedList<Entry>(); 
		logger.info(id+" "+manifestLogTags.size()+" tags specified");
		logger.info(id+" Plugin instantiated");
	}

	/**
	 * Get HTML text displaying the current status of the plugin.
	 * @return HTML text displaying the current status of the plugin.
	 */
	public synchronized String getStatusHTML() {
		String sizeLine = "<tr><td width=\"20%\">Number of entries</td><td>"+manifest.size()+"</td></tr>";
		return getStatusHTML(sizeLine);
	}

	/**
	 * Log a DicomObject.
	 */
	public void log(DicomObject dob) { 
		manifest.add(new Entry(dob));
	}
	
	/**
	 * Clear the log.
	 */
	public void clear() {
		manifest.clear();
	}
	
	/**
	 * Get the log as a CSV string.
	 */
	public String toCSV() {
		StringBuffer sb = new StringBuffer();
		for (Integer tagInteger : manifestLogTags) {
			int tag = tagInteger.intValue();
			String name = DicomObject.getElementName(tag);
			sb.append("\""+name+"\",");
		}				
		sb.append("\n");
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.toArray(eArray);
		Arrays.sort(eArray);
		for (Entry e : eArray) {
			sb.append(e.toCSV());
		}
		return sb.toString();
	}
	
	/**
	 * Get the log as an XML Document.
	 */
	public Document toXML() throws Exception {
		Document doc = XmlUtil.getDocument();
		Element root = doc.createElement("Manifest");
		doc.appendChild(root);
		Entry[] eArray = new Entry[manifest.size()];
		eArray = manifest.toArray(eArray);
		Arrays.sort(eArray);
		for (Entry e : eArray) {
			root.appendChild(e.toXML(doc));
		}
		return doc;
	}
	
	class Entry implements Comparable<Entry> {
		LinkedList<String> elements;
		public String sopiUID;
		public String patientID;
		public String studyInstanceUID;
		public String seriesInstanceUID;
		public Entry(DicomObject dob) {
			sopiUID = dob.getSOPInstanceUID();
			patientID = dob.getPatientID();
			studyInstanceUID = dob.getStudyInstanceUID();
			seriesInstanceUID = dob.getSeriesInstanceUID();
			elements = new LinkedList<String>();
			for (Integer tag : manifestLogTags) {
				int t = tag.intValue();
				String v = dob.getElementValue(t);
				elements.add(v);
			}
		}
		public String toCSV() {
			StringBuffer sb = new StringBuffer();
			for (String v : elements) {
				sb.append("\""+v+"\",");
			}
			sb.append("\n");
			return sb.toString();
		}
		public Element toXML(Document doc) {
			Element el = doc.createElement("Entry");
			el.setAttribute("SOPInstanceUID", sopiUID);
			for (int i=0; i<elements.size(); i++) {
				int tag = manifestLogTags.get(i);
				String name = DicomObject.getElementName(tag);
				String value = elements.get(i);
				Element e = doc.createElement("Element");
				e.setAttribute("name",name);
				e.setAttribute("value",value);
				el.appendChild(e);
			}
			return el;
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