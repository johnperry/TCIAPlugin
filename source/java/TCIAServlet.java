package edu.uams.tcia;

import java.io.File;
import java.util.LinkedList;
import java.util.Properties;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.plugin.Plugin;
import org.rsna.ctp.stdstages.anonymizer.LookupTable;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.ctp.stdstages.DirectoryImportService;
import org.rsna.ctp.stdstages.DirectoryStorageService;
import org.rsna.ctp.stdstages.HttpExportService;
import org.rsna.multipart.UploadedFile;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;
import org.rsna.server.Path;
import org.rsna.servlets.Servlet;
import org.rsna.util.ExcelWorksheet;
import org.rsna.util.FileUtil;
import org.rsna.util.HttpUtil;
import org.rsna.util.XmlUtil;
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
		
/**/	logger.debug(req.toString());

		//Make sure the user is authorized to do this.
		if (!req.userHasRole("admin") && !req.userHasRole("tcia")) {
			res.setResponseCode(res.forbidden);
			res.send();
			return;
		}

		//Set the Content-Type for most functions
		res.setContentType("xml");

		//Get the plugin.
		Plugin pl = Configuration.getInstance().getRegisteredPlugin(context);
		if ((pl != null) && (pl instanceof TCIAPlugin)) {
			TCIAPlugin plugin = (TCIAPlugin)pl;
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
					//Move files from the importStorage stage to the anonymizerInput stage.
					DirectoryStorageService fromStage = plugin.getImportStorage();
					DirectoryImportService toStage = plugin.getAnonymizerInput();
					boolean ok = moveFile(fromStage.getRoot(), toStage.getImportDirectory(), req.getParameter("file",""));
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("export")) {
					//Move files from the importStorage stage to the anonymizerInput stage.
					DirectoryStorageService fromStage = plugin.getAnonymizerStorage();
					DirectoryImportService toStage = plugin.getExportInput();
					boolean ok = moveFile(fromStage.getRoot(), toStage.getImportDirectory(), req.getParameter("file",""));
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("getQuarantineURL")) {
					//Return the URL of the DicomAnonymizer quarantine servlet
					DicomAnonymizer da = plugin.getAnonymizer();
					int pIndex = da.getPipeline().getPipelineIndex();
					int sIndex = da.getStageIndex();
					String qs = "?p="+pIndex+"&amp;s="+sIndex;
					String url = "/quarantines"+qs;
					res.write("<quarantine stage=\""+da.getName()+"\" url=\""+url+"\"/>");
				}
				else if (function.equals("getQuarantineSummary")) {
					//Return a summary of the files in the DicomAnonymizer quarasntine
					DicomAnonymizer da = plugin.getAnonymizer();
					//TBD
				}
				else if (function.equals("getAvailableSpace")) {
					File root = new File("/");
					String name = root.getAbsolutePath();
					long oneMB = 1024 * 1024;
					long free = root.getUsableSpace()/oneMB;
					res.write("<space partition=\""+name+"\" available=\""+free+"\" units=\"MB\"/>");
				}
				else if (function.equals("clearManifest")) {
					ManifestLogPlugin manifestLog = plugin.getExportManifestLog();
					manifestLog.clear();
					res.write("<OK/>");
				}
				else if (function.equals("listManifest")) {
					ManifestLogPlugin manifestLog = plugin.getExportManifestLog();
					if (path.length() > 2) {
						if (path.element(2).equals("csv")) {
							res.write(manifestLog.toCSV());
							res.setContentType("csv");
							res.setContentDisposition(new File("Manifest.csv"));
						}
						else {
							try { res.write(XmlUtil.toPrettyString(manifestLog.toXML())); }
							catch (Exception ex) { res.write("<UNABLE/>"); }
						}
					}
				}
				else if (function.equals("exportManifest")) {
					boolean ok = true;
					ManifestLogPlugin manifestLog = plugin.getExportManifestLog();
					File dir = plugin.getExportInput().getImportDirectory();
					try {
						String manifest = manifestLog.toCSV();
						File file = File.createTempFile("MAN-", ".csv", dir);
						ok = FileUtil.setText(file, manifest);
					}
					catch (Exception ex) { ok = false; }
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("getExportQueueSize")) {
					HttpExportService httpExport = plugin.getExportOutput();
					int size = httpExport.getQueueManager().size();
					res.write("<queue stage=\""+httpExport.getName()+"\" size=\""+size+"\"/>");
				}
				else if (function.equals("reset")) {
					ManifestLogPlugin manifestLog = plugin.getExportManifestLog();
					manifestLog.clear();
					clearDirectory(plugin.getImportStorage().getRoot());
					clearDirectory(plugin.getAnonymizerStorage().getRoot());
					res.write("<OK/>");
				}
				else if (function.equals("listFiles")) {
					try {
						File dir = new File(req.getParameter("dir","/")).getAbsoluteFile();
						File parent = dir.getParentFile();
						File[] files = dir.listFiles();
						Document doc = XmlUtil.getDocument();
						Element root = doc.createElement("dir");
						root.setAttribute("name", dir.getName());
						root.setAttribute(
							"parent", 
							((parent == null) ? "" : parent.getAbsolutePath())
						);
						doc.appendChild(root);
						for (File file : files) {
							if (file.isDirectory()) {
								Element e = doc.createElement("dir");
								e.setAttribute("name", file.getName());
								root.appendChild(e);
							}
						}
						for (File file : files) {
							if (file.isFile()) {
								Element e = doc.createElement("file");
								e.setAttribute("name", file.getName());
								root.appendChild(e);
							}
						}
						res.write(XmlUtil.toPrettyString(root));
					}
					catch (Exception ex) { res.write("<dir/>"); }
				}
				else if (function.equals("submitFile")) {
					boolean ok = true;
					try {
						String pathseq = req.getParameter("file");
						String[] paths = pathseq.split("\\|");
						File destdir = plugin.getImportInput().getImportDirectory();
						for (String p : paths) {
							File file = new File(p);
							if (file.exists()) ok &= submitFile(file, destdir);
							else ok = false;
						}
					}
					catch (Exception ex) { ok = false; }
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("listElements")) {
					try {
						File file = new File(req.getParameter("file"));
						DicomObject dob = new DicomObject(file);
						res.write("<html>\n<head>\n");
						res.write("<title>"+file.getName()+"</title>\n");
						res.write("<link rel=\"Stylesheet\" type=\"text/css\" media=\"all\" href=\"/DicomListing.css\"></link>");
						res.write("</head>\n<body>\n<center>\n");
						res.write(dob.getElementTable());
						res.write("</center>\n</body>\n</html>\n");
						res.setContentType("html");
					}
					catch (Exception ex) { res.setResponseCode(res.notfound); }
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
		LookupTable lut = LookupTable.getInstance(lutFile);
		Properties props = lut.getProperties();
		boolean ok = true;
		try {
			String sheetName = ExcelWorksheet.getWorksheetNames(spreadsheetFile).peek();
			ExcelWorksheet sheet = new ExcelWorksheet(spreadsheetFile, sheetName);
			int lastRow = sheet.getLastRow();
			String lastColumnID = sheet.getLastColumn();
			int lastColumn = ExcelWorksheet.getColumn(lastColumnID);
			for (int row=3; row<=lastRow; row++) {
				String phi = sheet.getCell("A" + row);
				for (int col=1; col<=lastColumn; col++) {
					String colID = ExcelWorksheet.getColumnID(col);
					String type = sheet.getCell(colID + "1");
					String replacement = sheet.getCell(colID + row);
					if (replacement != null) {
						String key = type + "/" + phi;
						String current = props.getProperty(key);
						if (current != null) {
							ok &= current.equals(replacement);
						}
						props.setProperty(key, replacement);
					}
				}
			}
			lut.save();
		}
		catch (Exception unable) { ok = false; }
		return ok;
	}
	
	//Delete all the files in a directory
	private boolean clearDirectory(File dir) {
		boolean ok = true;
		if (dir.exists() && dir.isDirectory()) {
			File[] files = dir.listFiles();
			for (File f : files) {
				logger.info("Deleting "+f);
				ok &= FileUtil.deleteAll(f);
			}
		}
		return ok;
	}
	
	//Move files from a storage directory to an import directory.
	//If the path identifies a file, move the file.
	//If the path identifies a directory move the contents of the
	//directory and all its subdirectories.
	//Note that the destination is a flat directory (with no substructure).
	private boolean moveFile(File fromDir, File toDir, String path) {
		if (path.equals("")) return false;
		File fromParent = (new File(fromDir.getAbsolutePath())).getParentFile();
		File file = new File(fromParent, path);
		if (!file.exists()) return false;
		return moveFile(file, toDir);
	}
	
	private boolean moveFile(File file, File toDir) {
		boolean ok = true;
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				ok &= moveFile(f, toDir);
			}
		}
		else if (file.isFile()) {
			FileObject fob = new FileObject(file);
			ok = fob.moveToDirectory(toDir);
		}
		return ok;
	}

	//Submit DICOM files to an import directory.
	//If the supplied file is a file, copy the file.
	//If the supplied file is a directory copy the contents of the
	//directory and all its subdirectories.
	//Note that the destination is a flat directory (with no substructure).
	private boolean submitFile(File file, File toDir) {
		boolean ok = true;
		if (!file.exists()) return false;
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			for (File f : files) {
				ok &= submitFile(f, toDir);
			}
		}
		else if (file.isFile()) {
			try {
				DicomObject dob = null;
				try { dob = new DicomObject(file); }
				catch (Exception ex) { return true; } //ignore non-DICOM files
				File destFile = File.createTempFile("DCM-", ".dcm", toDir);
				ok &= dob.copyTo(destFile);
			}
			catch (Exception ex) { ok = false; }
		}
		return ok;			
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
			catch (Exception skip) { logger.warn("oops", skip); }
		}
	}
	
	private void setAttributes(Element el, DicomObject dob) {
		el.setAttribute("PatientName", dob.getPatientName());
		el.setAttribute("PatientID", dob.getPatientID());
		el.setAttribute("StudyDate", dob.getStudyDate());	
		el.setAttribute("Modality", dob.getModality());
	}
}