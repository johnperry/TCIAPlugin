package edu.uams.tcia;

import java.io.File;
import java.util.LinkedList;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.plugin.Plugin;
import org.rsna.multipart.UploadedFile;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;
import org.rsna.server.Path;
import org.rsna.servlets.Servlet;
import org.rsna.util.FileUtil;
import org.rsna.util.XmlUtil;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.ctp.stdstages.DirectoryImportService;
import org.rsna.ctp.stdstages.DirectoryStorageService;
import org.w3c.dom.*;

/**
 * The Servlet that provides access to the TCIA pipelines for the TCIA wizard.
 */
public class TCIAServlet extends Servlet {

	static final Logger logger = Logger.getLogger(TCIAServlet.class);

	/**
	 * Construct a TCIAServlet. Note: the TCIAServlet
	 * is added to the ServletSelector by the TCIAPlugin.
	 * The context is defined by the Plugin to be the Plugin's
	 * ID. This provides the linkage between the TCIAServlet
	 * and the TCIAPlugin.
	 * @param root the root directory of the server.
	 * @param context the path identifying the servlet.
	 */
	public TCIAServlet(File root, String context) {
		super(root, context);
	}

	/**
	 * The GET handler. The first path element provides the ID of the TCIAPlugin, from which the various pipeline
	 * stage IDs may be obtained. The second path element provides the name of the requested function.
	 * @param req the request object
	 * @param res the response object
	 */
	public void doGet(HttpRequest req, HttpResponse res) throws Exception {
		
/**/	logger.info(req.toString());

		//Make sure the user is authorized to do this.
		if (!req.userHasRole("admin") && !req.userHasRole("tcia")) {
			res.setResponseCode(res.forbidden);
			res.send();
			return;
		}

		//Get the plugin.
		Plugin p = Configuration.getInstance().getRegisteredPlugin(context);
		if ((p != null) && (p instanceof TCIAPlugin)) {
			TCIAPlugin plugin = (TCIAPlugin)p;
			Path path = req.getParsedPath();
			
			//Handle a request with no function identification
			if (path.length() == 1) {
				//Return the configuration of the plugin
				res.write( XmlUtil.toPrettyString(plugin.getConfigElement()) );
			}
			
			else {
				String function = path.element(1);
				if (function.equals("listImport")) {
					//List the files in the import pipeline
					DirectoryStorageService stage = plugin.getImportStorage();
					File dir = stage.getRoot();
/**/				logger.info("Listing: "+dir);
					Element el = listFiles(dir);
					res.write(XmlUtil.toString(el));
				}
				else if (function.equals("listAnonymized")) {
					//List the files in the anonymizer pipeline
					DirectoryStorageService stage = plugin.getAnonymizerStorage();
					File dir = stage.getRoot();
					Element el = listFiles(dir);
					res.write(XmlUtil.toString(el));
				}
				else if (function.equals("anonymize")) {
					//Move the specified files from the importStorage stage to the anonymizerInput stage
					//...
				}
				else if (function.equals("export")) {
					//Move the specified files from the importStorage stage to the anonymizerInput stage
					//...
				}
				else {
					//Unknown function
					res.setResponseCode(res.notfound);
				}
			}
		}
		else {
			//Since the servlet was installed by the TCIAPlugin, this should never happen.
			//Protect against it anyway in case somebody does something funny with the ID.
			res.setResponseCode(res.notfound);
		}

		res.disableCaching();
		res.setContentType("xml");
		res.setContentEncoding(req);
		res.send();
	}

	/**
	 * The POST handler to receive a spreadsheet file and update the 
	 * lookup table of the DicomAnonymizer
	 * @param req The HttpRequest provided by the servlet container.
	 * @param res The HttpResponse provided by the servlet container.
	 */
	public void doPost(HttpRequest req, HttpResponse res) {

		//Make sure the user is authorized to do this.
		if (!req.userHasRole("admin") && !req.userHasRole("tcia")) {
			res.setResponseCode(res.forbidden);
			res.send();
			return;
		}

		//Get the plugin.
		Configuration config = Configuration.getInstance();
		Plugin p = config.getInstance().getRegisteredPlugin(context);
		if ((p != null) && (p instanceof TCIAPlugin)) {
			TCIAPlugin plugin = (TCIAPlugin)p;

			//Get the posted file
			File dir = FileUtil.createTempDirectory(root);
			int maxsize = 75*1024*1024; //MB
			try {
				LinkedList<UploadedFile> files = req.getParts(dir, maxsize);
				if (files.size() > 0) {
					File spreadsheetFile = files.peekFirst().getFile();
					DicomAnonymizer da = plugin.getAnonymizer();
					File lutFile = da.getLookupTableFile();
					if (updateLUT(lutFile, spreadsheetFile)) {
						res.write("<OK/>");
					}
					else {
						res.write("<NOTOK/>");
					}
				}
			}
			catch (Exception unable) {
				res.write("<NOTOK/>");
			}
			FileUtil.deleteAll(dir);
		}
		else res.write("<NOTOK/>");

		res.setContentType("xml");
		res.disableCaching();
		res.send();
	}
	
	private boolean updateLUT(File lutFile, File spreadsheetFile) {
		//...
		return true;
	}
	
	//List files
	private Element listFiles(File dir) {
		try {
			Document doc = XmlUtil.getDocument();
			Element root = doc.createElement("DicomFiles");
			doc.appendChild(root);
			listFiles(root, dir);
			return root;
		}
		catch (Exception ex) {
			logger.warn("Unable to create XML document", ex);
			return null;
		}
	}
	
	private void listFiles(Element parent, File file) {
		Document doc = parent.getOwnerDocument();
		if (file.isDirectory()) {
			Element dirEl = doc.createElement("dir");
			dirEl.setAttribute("name", file.getName());
			parent.appendChild(dirEl);
			for (File f : file.listFiles()) {
				listFiles(dirEl, f);
			}
		}
		else if (file.isFile()) {
			try {
				DicomObject dob = new DicomObject(file);
				Element fileEl = doc.createElement("DicomObject");
				fileEl.setAttribute("name", file.getName());
				setAttributes(fileEl, dob);
				parent.appendChild(fileEl);
			}
			catch (Exception skip) { logger.warn("oops" + skip); }
		}
	}
	
	private void setAttributes(Element el, DicomObject dob) {
		el.setAttribute("PatientName", dob.getPatientName());
		el.setAttribute("PatientID", dob.getPatientID());
		el.setAttribute("StudyDate", dob.getStudyDate());	
		el.setAttribute("Modality", dob.getModality());
	}
}