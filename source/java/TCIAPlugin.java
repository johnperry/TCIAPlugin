package edu.uams.tcia;

import java.io.BufferedReader;
import java.io.File;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.plugin.Plugin;
import org.rsna.ctp.plugin.AbstractPlugin;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.ctp.stdstages.DirectoryImportService;
import org.rsna.ctp.stdstages.DirectoryStorageService;
import org.rsna.ctp.stdstages.HttpExportService;
import org.rsna.server.HttpServer;
import org.rsna.server.Path;
import org.rsna.server.ServletSelector;
import org.rsna.server.User;
import org.rsna.server.Users;
import org.rsna.server.UsersXmlFileImpl;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * A Plugin to interface the TCIA wizard into CTP.
 */
public class TCIAPlugin extends AbstractPlugin {
	
	static final Logger logger = Logger.getLogger(TCIAPlugin.class);
	
	String importInputID;
	String importManifestLogID;
	String importStorageID;
	String anonymizerInputID;
	String anonymizerID;
	String anonymizerStorageID;
	String exportInputID;
	String exportOutputID;
	String exportManifestLogID;
	DirectoryImportService importInput;
	DirectoryStorageService importStorage;
	DirectoryImportService anonymizerInput;
	DicomAnonymizer anonymizer;
	DirectoryStorageService anonymizerStorage;
	DirectoryImportService exportInput;
	PosdaExportService exportOutput;
	ExportManifestLogPlugin exportManifestLog;
	ImportManifestLogPlugin importManifestLog;
	
	boolean abortImport = false;
	
	/**
	 * IMPORTANT: When the constructor is called, neither the
	 * pipelines nor the HttpServer have necessarily been
	 * instantiated. Any actions that depend on those objects
	 * must be deferred until the start method is called.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the plugin.
	 */
	public TCIAPlugin(Element element) {
		super(element);
		this.importInputID = element.getAttribute("importInputID").trim();
		this.importStorageID = element.getAttribute("importStorageID").trim();
		this.importManifestLogID = element.getAttribute("importManifestLogID").trim();
		this.anonymizerInputID = element.getAttribute("anonymizerInputID").trim();
		this.anonymizerID = element.getAttribute("anonymizerID").trim();
		this.anonymizerStorageID = element.getAttribute("anonymizerStorageID").trim();
		this.exportInputID = element.getAttribute("exportInputID").trim();
		this.exportOutputID = element.getAttribute("exportOutputID").trim();
		this.exportManifestLogID = element.getAttribute("exportManifestLogID").trim();
		this.importManifestLogID = element.getAttribute("importManifestLogID").trim();
		logger.info(id+" Plugin instantiated");
	}

	/**
	 * Start the plugin.
	 */
	public void start() {
		//Add the tcia role
		Users users = Users.getInstance();
		users.addRole("tcia");
		
		//Update the admin user - force the password to tcia,
		//and grant the admin user the tcia and qadmin roles
		if (users instanceof UsersXmlFileImpl) {
			UsersXmlFileImpl usersXmlFileImpl = (UsersXmlFileImpl)users;
			User adminUser = users.getUser("admin");
			if (adminUser == null) {
				adminUser = usersXmlFileImpl.createUser("admin", "tcia");
			}
			else adminUser.setPassword(users.convertPassword("tcia"));
			adminUser.addRole("tcia");
			adminUser.addRole("aadmin");
			adminUser.addRole("qadmin");
			adminUser.addRole("shutdown");
			usersXmlFileImpl.addUser(adminUser);
		}
		
		//Get all the referenced stages
		importInput = getDISStage(importInputID);
		importStorage = getDSSStage(importStorageID);
		anonymizerInput = getDISStage(anonymizerInputID);
		anonymizer = getDAStage(anonymizerID);
		anonymizerStorage = getDSSStage(anonymizerStorageID);
		exportInput = getDISStage(exportInputID);
		exportOutput = getPosdaExportStage(exportOutputID);
		
		//Get the ManifestLogs
		importManifestLog = getImportManifestLogPlugin(importManifestLogID);
		exportManifestLog = getExportManifestLogPlugin(exportManifestLogID);
		
		//Install the TCIAServlet
		Configuration config = Configuration.getInstance();
		HttpServer server = config.getServer();
		ServletSelector selector = server.getServletSelector();
		selector.addServlet(id, TCIAServlet.class);
		
		//Put the .metadata_never_index file in the parent of the root directory
		File parentDir = root.getParentFile();
		File mniFile = new File(parentDir, ".metadata_never_index");
		root.mkdirs(); //just to make sure
		try { mniFile.createNewFile(); }
		catch (Exception ex) { logger.warn("Unable to create "+mniFile); }
				
		logger.info("TCIAPlugin started with context \""+id+"\"");
	}
	
	private DirectoryStorageService getDSSStage(String id) {
		PipelineStage stage = Configuration.getInstance().getRegisteredStage(id);
		if (stage == null) {
			logger.warn(name+": referenced stage does not exist ("+id+")");
			return null;
		}
		if (stage instanceof DirectoryStorageService) {
			return (DirectoryStorageService)stage;
		}
		else {
			logger.warn(name+": referenced stage is not a DirectoryStorageService ("+id+")");
			return null;
		}		
	}
	
	private DirectoryImportService getDISStage(String id) {
		PipelineStage stage = Configuration.getInstance().getRegisteredStage(id);
		if (stage == null) {
			logger.warn(name+": referenced stage does not exist ("+id+")");
			return null;
		}
		if (stage instanceof DirectoryImportService) {
			return (DirectoryImportService)stage;
		}
		else {
			logger.warn(name+": referenced stage is not a DirectoryImportService ("+id+")");
			return null;
		}		
	}
	
	private DicomAnonymizer getDAStage(String id) {
		PipelineStage stage = Configuration.getInstance().getRegisteredStage(id);
		if (stage == null) {
			logger.warn(name+": referenced stage does not exist ("+id+")");
			return null;
		}
		if (stage instanceof DicomAnonymizer) {
			return (DicomAnonymizer)stage;
		}
		else {
			logger.warn(name+": referenced stage is not a DicomAnonymizer ("+id+")");
			return null;
		}		
	}
	
	private PosdaExportService getPosdaExportStage(String id) {
		PipelineStage stage = Configuration.getInstance().getRegisteredStage(id);
		if (stage == null) {
			logger.warn(name+": referenced stage does not exist ("+id+")");
			return null;
		}
		if (stage instanceof PosdaExportService) {
			return (PosdaExportService)stage;
		}
		else {
			logger.warn(name+": referenced stage is not a PosdaExportService ("+id+")");
			return null;
		}		
	}
	
	private ExportManifestLogPlugin getExportManifestLogPlugin(String id) {
		Plugin plugin = Configuration.getInstance().getRegisteredPlugin(id);
		if (plugin == null) {
			logger.warn(name+": referenced plugin does not exist ("+id+")");
			return null;
		}
		if (plugin instanceof ExportManifestLogPlugin) {
			return (ExportManifestLogPlugin)plugin;
		}
		else {
			logger.warn(name+": referenced plugin is not an ExportManifestLogPlugin ("+id+")");
			return null;
		}		
	}
	
	private ImportManifestLogPlugin getImportManifestLogPlugin(String id) {
		Plugin plugin = Configuration.getInstance().getRegisteredPlugin(id);
		if (plugin == null) {
			logger.warn(name+": referenced plugin does not exist ("+id+")");
			return null;
		}
		if (plugin instanceof ImportManifestLogPlugin) {
			return (ImportManifestLogPlugin)plugin;
		}
		else {
			logger.warn(name+": referenced plugin is not an ImportManifestLogPlugin ("+id+")");
			return null;
		}		
	}
	
	/**
	 * Get the DirectoryImportService receiving files in the import pipeline.
	 */
	public DirectoryImportService getImportInput() {
		return importInput;
	}
	
	/**
	 * Get the DirectoryStorageService holding the objects received by the import pipeline.
	 */
	public DirectoryStorageService getImportStorage() {
		return importStorage;
	}
	
	/**
	 * Get the DirectoryImportService holding the objects ready for processing by the anonymizer pipeline.
	 */
	public DirectoryImportService getAnonymizerInput() {
		return anonymizerInput;
	}
	
	/**
	 * Get the DicomAnonymizer.
	 */
	public DicomAnonymizer getAnonymizer() {
		return anonymizer;
	}

	/**
	 * Get the DirectoryStorageService holding the objects already processed by the DicomAnonymizer.
	 */
	public DirectoryStorageService getAnonymizerStorage() {
		return anonymizerStorage;
	}
	
	/**
	 * Get the DirectoryImportService holding the objects ready for export by the export pipeline.
	 */
	public DirectoryImportService getExportInput() {
		return exportInput;
	}
	
	/**
	 * Get the PosdaExportService of the export pipeline.
	 */
	public PosdaExportService getExportOutput() {
		return exportOutput;
	}
	
	/**
	 * Get the ExportManifestLog.
	 */
	public ExportManifestLogPlugin getExportManifestLog() {
		return exportManifestLog;
	}
	
	/**
	 * Get the ImportManifestLog.
	 */
	public ImportManifestLogPlugin getImportManifestLog() {
		return importManifestLog;
	}
	
	/**
	 * Set the abortImport flag.
	 */
	public synchronized void setAbortImport(boolean abortImport) {
		this.abortImport = abortImport;
	}
	
	/**
	 * Get the abortImport flag.
	 */
	public synchronized boolean getAbortImport() {
		return this.abortImport;
	}
	
}