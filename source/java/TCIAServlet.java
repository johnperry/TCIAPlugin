package edu.uams.tcia;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.LinkedList;
import java.util.GregorianCalendar;
import java.util.Properties;
import javax.swing.filechooser.FileSystemView;
import org.apache.log4j.Logger;
import org.apache.poi.ss.usermodel.DateUtil;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.pipeline.AbstractImportService;
import org.rsna.ctp.pipeline.AbstractQueuedExportService;
import org.rsna.ctp.pipeline.Pipeline;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.pipeline.QueueManager;
import org.rsna.ctp.plugin.Plugin;
import org.rsna.ctp.stdstages.anonymizer.LookupTable;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.ctp.stdstages.DirectoryImportService;
import org.rsna.ctp.stdstages.DirectoryStorageService;
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
	
	TCIAPlugin tciaPlugin = null;
	ExportManifestLogPlugin exportManifestPlugin = null;
	ImportManifestLogPlugin importManifestPlugin = null;

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
		
		if (logger.isDebugEnabled()) logger.debug(req.toString());

		//Make sure the user is authorized to do this.
		if (!req.userHasRole("admin") && !req.userHasRole("tcia")) {
			res.setResponseCode(res.forbidden);
			res.send();
			return;
		}

		//Set the Content-Type for most functions
		res.setContentType("xml");
		
		//Suppress caching
		res.disableCaching();

		//Get the plugins.
		Plugin pl = Configuration.getInstance().getRegisteredPlugin(context);
		if ((pl != null) && (pl instanceof TCIAPlugin)) {
			tciaPlugin = (TCIAPlugin)pl;
			exportManifestPlugin = tciaPlugin.getExportManifestLog();
			importManifestPlugin = tciaPlugin.getImportManifestLog();

			Path path = req.getParsedPath();
			
			//Handle a request with no function identification
			if (path.length() == 1) {
				//Return the configuration of the tciaPlugin
				res.write( XmlUtil.toPrettyString(tciaPlugin.getConfigElement()) );
			}
			
			else {
				String function = path.element(1);
				if (function.equals("listImport")) {
					//List the files in the import pipeline
					DirectoryStorageService stage = tciaPlugin.getImportStorage();
					File dir = stage.getRoot();
					Element el = listFiles(dir);
					res.write(XmlUtil.toString(el));
				}
				else if (function.equals("listAnonymized")) {
					//List the files in the anonymizer pipeline
					DirectoryStorageService stage = tciaPlugin.getAnonymizerStorage();
					File dir = stage.getRoot();
					Element el = listFiles(dir);
					res.write(XmlUtil.toString(el));
				}
				else if (function.equals("anonymize")) {
					//Move files from the importStorage stage to the anonymizerInput stage.
					DirectoryStorageService fromStage = tciaPlugin.getImportStorage();
					DirectoryImportService toStage = tciaPlugin.getAnonymizerInput();
					boolean ok = moveFile(fromStage.getRoot(), toStage.getImportDirectory(), req.getParameter("file",""), true);
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("export")) {
					//Move files from the AnonymizerStorage stage to the ExportInput stage.
					DirectoryStorageService fromStage = tciaPlugin.getAnonymizerStorage();
					DirectoryImportService toStage = tciaPlugin.getExportInput();
					boolean ok = exportFile(fromStage.getRoot(), toStage.getImportDirectory(), req.getParameter("file",""));
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("getQuarantineURL")) {
					//Return the URL of the DicomAnonymizer quarantine servlet
					DicomAnonymizer da = tciaPlugin.getAnonymizer();
					int pIndex = da.getPipeline().getPipelineIndex();
					int sIndex = da.getStageIndex();
					String qs = "?p="+pIndex+"&amp;s="+sIndex;
					String url = "/quarantines"+qs;
					res.write("<quarantine stage=\""+da.getName()+"\" url=\""+url+"\"/>");
				}
				else if (function.equals("getQuarantineSummary")) {
					//Return a summary of the files in the DicomAnonymizer quarasntine
					DicomAnonymizer da = tciaPlugin.getAnonymizer();
					//TBD
				}
				else if (function.equals("getFileSystemRoots")) {
					FileSystemView fsv = FileSystemView.getFileSystemView();
					File[] roots = File.listRoots();
					StringBuffer sb = new StringBuffer();
					sb.append("<roots>");
					for (File root : roots) {
						sb.append("<root name=\""+root.getAbsolutePath()+"\" ");
						sb.append("desc=\""+fsv.getSystemTypeDescription(root)+"\"/>");
					}
					sb.append("</roots>");
					res.write(sb.toString());
				}
				else if (function.equals("getAvailableSpace")) {
					File root = new File(req.getParameter("root", "/"));
					String name = root.getAbsolutePath();
					long oneMB = 1024 * 1024;
					long free = root.getUsableSpace()/oneMB;
					String units = "MB";
					if (free > 2000) {
						free /= 1000;
						units = "GB";
					}
					res.write("<space partition=\""+name+"\" available=\""+free+"\" units=\""+units+"\"/>");
				}
				else if (function.equals("clearExportManifest")) {
					exportManifestPlugin.clear();
					res.write("<OK/>");
				}
				else if (function.equals("initializeAnonymizerPipelineCounts")) {
					Document doc = exportManifestPlugin.initializeAnonymizerPipelineCounts();
					if (doc != null) res.write(XmlUtil.toString(doc));
					else res.setResponseCode(res.notfound);
				}
				else if (function.equals("listImportManifest")) {
					if (path.length() > 2) {
						if (path.element(2).equals("csv")) {
							res.write(importManifestPlugin.toCSV());
							res.setContentType("csv");
							res.setContentDisposition(new File("ImportManifest.csv"));
						}
						else if (path.element(2).equals("xml")) {
							try { res.write(XmlUtil.toPrettyString(importManifestPlugin.toXML())); }
							catch (Exception ex) { res.write("<UNABLE/>"); }
						}
						else if (path.element(2).equals("xlsx")) {
							res.write(importManifestPlugin.toXLSX());
							res.setContentType("xlsx");
							res.setContentDisposition(new File("ImportManifest.xlsx"));
						}
					}
				}
				else if (function.equals("listLocalManifest")) {
					if (path.length() > 2) {
						if (path.element(2).equals("csv")) {
							res.write(exportManifestPlugin.toCSV(true));
							res.setContentType("csv");
							res.setContentDisposition(new File("LocalManifest.csv"));
						}
						else if (path.element(2).equals("xml")) {
							try { res.write(XmlUtil.toPrettyString(exportManifestPlugin.toXML(true))); }
							catch (Exception ex) { res.write("<UNABLE/>"); }
						}
						else if (path.element(2).equals("xlsx")) {
							res.write(exportManifestPlugin.toXLSX(true));
							res.setContentType("xlsx");
							res.setContentDisposition(new File("LocalManifest.xlsx"));
						}
					}
				}
				else if (function.equals("listHistory")) {
					boolean includePHI = (path.length() > 2) && path.element(2).equals("phi");
					res.write(exportManifestPlugin.toHistoryXLSX(includePHI));
					res.setContentType("xlsx");
					res.setContentDisposition(new File("History"+(includePHI?"(PHI)":"")+".xlsx"));
				}
				else if (function.equals("exportHistory")) {
					boolean ok = true;
					File dir = tciaPlugin.getExportInput().getImportDirectory();
					try {
						byte[] history = exportManifestPlugin.toHistoryXLSX(false);
						File file = File.createTempFile("HIS-", ".xlsx", dir);
						FileOutputStream fos = new FileOutputStream(file);
						BufferedOutputStream bos = new BufferedOutputStream(fos);
						bos.write(history, 0, history.length);
						bos.flush();
						bos.close();
						ok = true;
					}
					catch (Exception ex) { ok = false; }
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("listLookupTableTemplate")) {
					res.write(importManifestPlugin.getLookupTableTemplate(req.getParameter("id")));
					res.setContentType("xlsx");
					res.setContentDisposition(new File("LookupTableTemplate.xlsx"));
				}
				else if (function.equals("listExportManifest")) {
					if (path.length() > 2) {
						if (path.element(2).equals("csv")) {
							res.write(exportManifestPlugin.toCSV(false));
							res.setContentType("csv");
							res.setContentDisposition(new File("ExportManifest.csv"));
						}
						else if (path.element(2).equals("xml")) {
							try { res.write(XmlUtil.toPrettyString(exportManifestPlugin.toXML(false))); }
							catch (Exception ex) { res.write("<UNABLE/>"); }
						}
						else if (path.element(2).equals("xlsx")) {
							res.write(exportManifestPlugin.toXLSX(false));
							res.setContentType("xlsx");
							res.setContentDisposition(new File("ExportManifest.xlsx"));
						}
					}
				}
				else if (function.equals("getExportManifestStatus")) {
					Document doc = exportManifestPlugin.getManifestStatus();
					if (doc != null) res.write(XmlUtil.toString(doc));
					else res.setResponseCode(res.notfound);
				}
				else if (function.equals("getImportStatus")) {
					Pipeline pipe = tciaPlugin.getImportStorage().getPipeline();
					int queueSize = 0;
					for (PipelineStage stage : pipe.getStages()) {
						if (stage instanceof AbstractImportService) {
							queueSize += ((AbstractImportService)stage).getQueueManager().size();
						}
					}
					res.write("<status queueSize=\""+queueSize+"\"/>");
				}
				else if (function.equals("getImportManifestInstanceCount")) {
					int count = importManifestPlugin.getManifestInstanceCount();
					res.write("<status instanceCount=\""+count+"\"/>");
				}
				else if (function.equals("exportManifest")) {
					boolean ok = true;
					File dir = tciaPlugin.getExportInput().getImportDirectory();
					try {
						String manifest = exportManifestPlugin.toCSV(false);
						File file = File.createTempFile("MAN-", ".csv", dir);
						ok = FileUtil.setText(file, manifest);
					}
					catch (Exception ex) { ok = false; }
					res.write( ok ? "<OK/>" : "<NOTOK/>" );
				}
				else if (function.equals("getExportQueueSize")) {
					AbstractQueuedExportService exportService = tciaPlugin.getExportOutput();
					int size = exportService.getQueueManager().size();
					res.write("<queue stage=\""+exportService.getName()+"\" size=\""+size+"\"/>");
				}
				else if (function.equals("listFiles")) {
					try {
						boolean dcmOnly = req.hasParameter("dcm");
						int acceptedFileCount = 0;
						int skippedFileCount = 0;
						File dir = new File(req.getParameter("dir","/")).getAbsoluteFile();
						File parent = dir.getParentFile();
						File[] files = dir.listFiles();
						Document doc = XmlUtil.getDocument();
						Element root = doc.createElement("dir");
						String name = dir.getName();
						if (name.equals("")) name = dir.getAbsolutePath();
						root.setAttribute("name", name);
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
								boolean isDICOM = isDICOM(file);
								if (!dcmOnly || isDICOM) {
									acceptedFileCount++;
									Element e = doc.createElement("file");
									e.setAttribute("name", file.getName());
									root.appendChild(e);
								}
								else if (dcmOnly) skippedFileCount++;
							}
						}
						root.setAttribute("acceptedFileCount", Integer.toString(acceptedFileCount));
						root.setAttribute("skippedFileCount", Integer.toString(skippedFileCount));
						res.write(XmlUtil.toPrettyString(root));
					}
					catch (Exception ex) { res.write("<dir/>"); }
				}
				else if (function.equals("getSpaceRequired")) {
					FileInfo info = new FileInfo();
					String pathseq = req.getParameter("file", req.getParameter("files"));
					String[] paths = pathseq.split("\\|");
					for (String p : paths) {
						File file = new File(p);
						if (file.exists()) info.add(getInfo(file));
					}
					File root = new File("/");
					String name = root.getAbsolutePath();
					long oneMB = 1024 * 1024;
					long free = root.getUsableSpace()/oneMB;
					long total = root.getTotalSpace() / oneMB;
					long size = info.size / oneMB;
					int n = info.n;
					String units = "MB";
					
					res.write(
							"<space partition=\""+name+"\" files=\""+n+"\"" +
							" required=\""+size+"\" available=\""+free+"\"" +
							" total=\""+total+"\" units=\""+units+"\"/>");
				}
				else if (function.equals("submitFile") || function.equals("submitFiles")) {
					Status status = new Status();
					tciaPlugin.setAbortImport(false);
					try {
						String pathseq = req.getParameter("file", req.getParameter("files"));
						String[] paths = pathseq.split("\\|");
						DirectoryImportService dis = tciaPlugin.getImportInput();
						File destdir = dis.getImportDirectory();
						QueueManager queue = dis.getQueueManager();
						for (String p : paths) {
							File file = new File(p);
							if (file.exists()) submitFile(status, file, destdir, queue, tciaPlugin);
						}
					}
					catch (Exception ex) { status.update( false ); }
					res.write( status.success ? "<OK" : "<NOTOK" ); 
					res.write( " acceptedFileCount=\"" + status.acceptedFileCount + "\"" );
					res.write( " skippedFileCount=\"" + status.skippedFileCount + "\"" );
					res.write( "/>" ); 
				}
				else if (function.equals("abortImport")) {
					tciaPlugin.setAbortImport(true);
					res.write( "<OK/>" );
				}
				else if (function.equals("listElements")) {
					File file = new File(req.getParameter("file"));
					try {
						DicomObject dob = new DicomObject(file);
						res.write("<html>\n<head>\n");
						res.write("<title>"+file.getName()+"</title>\n");
						res.write("<link rel=\"Stylesheet\" type=\"text/css\" media=\"all\" href=\"/DicomListing.css\"></link>");
						res.write("</head>\n<body>\n<center>\n");
						res.write(dob.getElementTable());
						res.write("</center>\n</body>\n</html>\n");
						res.setContentType("html");
					}
					catch (Exception ex) { 
						if (!file.exists()) res.setResponseCode(res.notfound); 
						else {
							res.setResponseCode(res.servererror);
							logger.warn("Unable to parse "+file, ex);
						}
					}
				}
				else if (function.equals("getImage")) {
					File file = new File(req.getParameter("file"));
					try {
						DicomObject dob = new DicomObject(file);
						File jpeg = File.createTempFile("DCM-", ".jpeg");
						dob.saveAsJPEG(jpeg, 0, 1024, 512, -1);
						res.write(jpeg);
						res.setContentType(jpeg);
					}
					catch (Exception ex) { 
						if (!file.exists()) res.setResponseCode(res.notfound); 
						else {
							res.setResponseCode(res.servererror);
							logger.warn("Unable to get Image for "+file, ex);
						}
					}
				}
				else if (function.equals("pause")) {
					PipelineStage anonymizer = tciaPlugin.getAnonymizer();
					Pipeline pipe = anonymizer.getPipeline();
					pipe.setPaused(true);
					res.write( "<OK/>" );
				}
				else if (function.equals("exportStatus")) {
					DirectoryImportService dis = tciaPlugin.getExportInput();
					AbstractQueuedExportService hes = tciaPlugin.getExportOutput();
					int n = FileUtil.getFileCount(dis.getImportDirectory()) 
							+ dis.getQueueManager().size()
							+ (hes.hasCache() ? hes.getCacheManager().size() : 0)
							+ hes.getQueueManager().size();
					if (n > 0) res.write("<ACTIVE/>");
					else res.write("<INACTIVE/>");
				}
				else if (function.equals("restart")) {
					DicomAnonymizer anonymizer = tciaPlugin.getAnonymizer();
					Pipeline pipe = anonymizer.getPipeline();
					if (pipe.isPaused()) {
						if ((path.length() > 2) && path.element(2).equals("requeue")) {
							DirectoryImportService dis = tciaPlugin.getAnonymizerInput();
							anonymizer.getQuarantine().queueAll(dis.getQueueManager());
						}
						pipe.setPaused(false);
						res.write( "<OK/>" );
					}
					else res.write( "<NOTOK/>" );
				}
				else if (function.equals("reset")) {
					ExportManifestLogPlugin exportManifestLog = tciaPlugin.getExportManifestLog();
					exportManifestLog.clear();
					ImportManifestLogPlugin importManifestLog = tciaPlugin.getImportManifestLog();
					importManifestLog.clear();
					clearDirectory(tciaPlugin.getImportStorage().getRoot());
					clearDirectory(tciaPlugin.getAnonymizerStorage().getRoot());
					tciaPlugin.getAnonymizer().getQuarantine().deleteAll();
					exportManifestLog.initializeAnonymizerPipelineCounts();
					res.write("<OK/>");
				}
				else if (function.equals("resetHistory")) {
					ExportManifestLogPlugin exportManifestLog = tciaPlugin.getExportManifestLog();
					exportManifestLog.clearHistory();
					res.write("<OK/>");
				}
				else if (function.equals("dashboard")) {
					ExportManifestLogPlugin exportManifestLog = tciaPlugin.getExportManifestLog();
					Document doc = XmlUtil.getDocument();
					Element root = doc.createElement("Dashboard");
					doc.appendChild(root);
					
					Element impsts = doc.createElement("ImportStatus");
					impsts.setAttribute("storedFiles", 
										Integer.toString(FileUtil.getFileCount(tciaPlugin.getImportStorage().getRoot())));
					root.appendChild(impsts);
					
					Element ansts = (Element)doc.importNode(exportManifestLog.getManifestStatus().getDocumentElement(), true);
					doc.renameNode(ansts, ansts.getNamespaceURI(), "AnonymizerStatus");
					root.appendChild(ansts);
					ansts.setAttribute("storedFiles", 
										Integer.toString(FileUtil.getFileCount(tciaPlugin.getAnonymizerStorage().getRoot())));					
					
					res.write(XmlUtil.toPrettyString(root));
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

		//Get the tciaPlugin.
		Configuration config = Configuration.getInstance();
		Plugin p = config.getInstance().getRegisteredPlugin(context);
		if ((p != null) && (p instanceof TCIAPlugin)) {
			TCIAPlugin tciaPlugin = (TCIAPlugin)p;

			//Get the posted file
			File dir = FileUtil.createTempDirectory(root);
			int maxsize = 75*1024*1024; //MB
			try {
				LinkedList<UploadedFile> files = req.getParts(dir, maxsize);
				if (files.size() > 0) {
					File spreadsheetFile = files.peekFirst().getFile();
					DicomAnonymizer da = tciaPlugin.getAnonymizer();
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
	
	private boolean isDICOM(File file) {
		try {
			DicomObject dob = new DicomObject(file);
			return true;
		}
		catch (Exception ex) { }
		return false;
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
						if (type.contains("date")) {
							//See if the replacement is an Excel
							//numeric date, and if so, convert it to text.
							try {
								double d = Double.parseDouble(replacement);
								Date date = DateUtil.getJavaDate(d);
								GregorianCalendar gc = new GregorianCalendar();
								gc.setTime(date);
								int year = gc.get(gc.YEAR);
								int month = gc.get(gc.MONTH) + 1;
								int day = gc.get(gc.DAY_OF_MONTH);
								replacement = String.format("%d/%d/%4d", month, day, year);
							}
							catch (Exception notNumericDate) { }
						}
						String key = type + "/" + phi;
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
	
	//Move files from a storage directory to an import directory for an anonymizer pipeline.
	//If the path identifies a file, move the file.
	//If the path identifies a directory move the contents of the
	//directory and all its subdirectories.
	//Note that the destination is a flat directory (with no substructure).
	private boolean moveFile(File fromDir, File toDir, String path, boolean log) {
		if (path.equals("")) return false;
		File fromParent = (new File(fromDir.getAbsolutePath())).getParentFile();
		File file = new File(fromParent, path);
		if (!file.exists()) return false;
		return moveFile(file, toDir, true, log);
	}
	
	private boolean moveFile(File file, File toDir, boolean isRoot, boolean log) {
		boolean ok = true;
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				ok &= moveFile(f, toDir, false, log);
			}
			if (!isRoot && (file.listFiles().length == 0)) {
				file.delete();
			}
		}
		else if (file.isFile()) {
			FileObject fob = new FileObject(file);
			ok = fob.moveToDirectory(toDir);
			if (log) exportManifestPlugin.incrementQueuedInstance();
		}
		return ok;
	}

	//Move files from a storage directory to an import directory for an export pipeline.
	//If the path identifies a file, move the file.
	//If the path identifies a directory move the contents of the
	//directory and all its subdirectories.
	//Note that the destination is a flat directory (with no substructure).
	private boolean exportFile(File fromDir, File toDir, String path) {
		if (path.equals("")) return false;
		File fromParent = (new File(fromDir.getAbsolutePath())).getParentFile();
		File file = new File(fromParent, path);
		if (!file.exists()) return false;
		return exportFile(file, toDir, true);
	}
	
	private boolean exportFile(File file, File toDir, boolean isRoot) {
		boolean ok = true;
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				ok &= exportFile(f, toDir, false);
			}
			if (!isRoot && (file.listFiles().length == 0)) {
				file.delete();
			}
		}
		else if (file.isFile()) {
			FileObject fob = FileObject.getInstance(file);
			ok = fob.moveToDirectory(toDir);
			if (fob instanceof DicomObject) {
				exportManifestPlugin.logExportedObject( (DicomObject)fob );
			}
		}
		return ok;
	}

	//Submit DICOM files to an import directory.
	//If the supplied file is a file, copy the file.
	//If the supplied file is a directory copy the contents of the
	//directory and all its subdirectories.
	//Note that the destination is a flat directory (with no substructure).
	private void submitFile(Status status, File file, File toDir, QueueManager queue, TCIAPlugin tciaPlugin) {
		if (file.exists() && !tciaPlugin.getAbortImport()) {
			if (file.isDirectory()) {
				File[] files = file.listFiles();
				for (File f : files) {
					submitFile(status, f, toDir, queue, tciaPlugin);
				}
			}
			else if (file.isFile()) {
				try {
					DicomObject dob = null;
					try { dob = new DicomObject(file); }
					catch (Exception ex) { 
						status.countSkippedFile();
						return;
					}
					File destFile = File.createTempFile("DCM-", ".partial", toDir);
					boolean ok = dob.copyTo(destFile);
					status.update( ok );
					if (ok) status.countAcceptedFile();
					queue.enqueue(destFile);
					destFile.delete();
				}
				catch (Exception ex) { status.update( false ); }
			}
		}
	}
	
	class Status {
		public int acceptedFileCount = 0;
		public int skippedFileCount = 0;
		public boolean success = true;
		public Status() { }
		public void countAcceptedFile() {
			acceptedFileCount++;
		}
		public void countSkippedFile() {
			skippedFileCount++;
		}
		public void update(boolean ok) {
			success &= ok;
		}
	}
		
	
	//List files
	private Element listFiles(File dir) {
		try {
			Document doc = XmlUtil.getDocument();
			Element root = doc.createElement("DicomFiles");
			doc.appendChild(root);
			int count = listFiles(root, dir, true);
			root.setAttribute("count", Integer.toString(count));
			return root;
		}
		catch (Exception ex) {
			logger.warn("Unable to create XML document", ex);
			return null;
		}
	}
	
	private int listFiles(Element parent, File file, boolean showParent) {
		Document doc = parent.getOwnerDocument();
		int count = 0;
		if (file.isDirectory()) {
			Element dirEl = doc.createElement("dir");
			dirEl.setAttribute("name", file.getName());
			if (showParent) {
				File parentFile = file.getParentFile();
				dirEl.setAttribute("parent", parentFile.getAbsolutePath());
			}
			parent.appendChild(dirEl);
			for (File f : file.listFiles()) {
				count += listFiles(dirEl, f, false);
			}
			dirEl.setAttribute("count", Integer.toString(count));
			Element ch = (Element)dirEl.getFirstChild();
			if (ch != null) {
				String ptid = ch.getAttribute("PatientID");
				dirEl.setAttribute("PatientID", ptid);
			}
		}
		else if (file.isFile()) {
			try {
				DicomObject dob = new DicomObject(file);
				Element fileEl = doc.createElement("DicomObject");
				fileEl.setAttribute("name", file.getName());
				setAttributes(fileEl, dob);
				parent.appendChild(fileEl);
				count++;
			}
			catch (Exception skip) { logger.warn("oops", skip); }
		}
		return count;
	}
	
	private FileInfo getInfo(File file) {
		FileInfo info = new FileInfo();
		if ((file != null) && file.exists()) {
			if (file.isFile()) {
				info.add(file.length());
			}
			else {
				File[] files = file.listFiles();
				for (File f : files) {
					try { info.add(getInfo(f)); }
					catch (Exception ex) {
						logger.warn("Unable to getInfo for "+f);
					}
				}
			}
		}
		return info;
	}
	
	class FileInfo {
		public int n = 0;
		public long size = 0;
		public FileInfo() { }
		public FileInfo add(long size) {
			this.n++;
			this.size += size;
			return this;
		}
		public FileInfo add(FileInfo info) {
			this.n += info.n;
			this.size += info.size;
			return this;
		}
	}
	
	private void setAttributes(Element el, DicomObject dob) {
		el.setAttribute("PatientName", dob.getPatientName());
		el.setAttribute("PatientID", dob.getPatientID());
		el.setAttribute("StudyDate", dob.getStudyDate());	
		el.setAttribute("Modality", dob.getModality());
		el.setAttribute("Series", dob.getSeriesNumber());
	}
}