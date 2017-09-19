package edu.uams.tcia;

import java.io.BufferedReader;
import java.io.File;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.plugin.AbstractPlugin;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.ctp.stdstages.DicomAnonymizer;
import org.rsna.ctp.stdstages.DirectoryImportService;
import org.rsna.ctp.stdstages.DirectoryStorageService;
import org.rsna.server.HttpServer;
import org.rsna.server.Path;
import org.rsna.server.ServletSelector;
import org.rsna.server.Users;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * A Plugin to interface the TCIA wizard into CTP.
 */
public class TCIAPlugin extends AbstractPlugin {
	
	static final Logger logger = Logger.getLogger(TCIAPlugin.class);
	
	String importStorageID;
	String anonymizerInputID;
	String anonymizerID;
	String anonymizerStorageID;
	String exportInputID;
	DirectoryStorageService importStorage;
	DirectoryImportService anonymizerInput;
	DicomAnonymizer anonymizer;
	DirectoryStorageService anonymizerStorage;
	DirectoryImportService exportInput;
	
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
		this.importStorageID = element.getAttribute("importStorageID").trim();
		this.anonymizerInputID = element.getAttribute("anonymizerInputID").trim();
		this.anonymizerID = element.getAttribute("anonymizerID").trim();
		this.anonymizerStorageID = element.getAttribute("anonymizerStorageID").trim();
		this.exportInputID = element.getAttribute("exportInputID").trim();
		logger.info(id+" Plugin instantiated");
	}

	/**
	 * Start the plugin.
	 */
	public void start() {
		//Add the tcia role
		Users.getInstance().addRole("tcia");
		
		//Install the TCIAServlet
		Configuration config = Configuration.getInstance();
		HttpServer server = config.getServer();
		ServletSelector selector = server.getServletSelector();
		selector.addServlet(id, TCIAServlet.class);
		
		//Get all the referenced stages
		importStorage = getDSSStage(importStorageID);
		anonymizerInput = getDISStage(anonymizerInputID);
		anonymizer = getDAStage(anonymizerID);
		anonymizerStorage = getDSSStage(anonymizerStorageID);
		exportInput = getDISStage(exportInputID);
		
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
	
	/**
	 * Get the ID of the DirectoryStorageService holding the objects received by the import pipeline.
	 */
	public String getImportStorageID() {
		return importStorageID;
	}
	
	/**
	 * Get the ID of the DirectoryImportService holding the objects ready for processing by the anonymizer pipeline.
	 */
	public String getAnonymizerInputID() {
		return anonymizerInputID;
	}
	
	/**
	 * Get the ID of the DicomAnonymizer.
	 */
	public String getAnonymizerID() {
		return anonymizerID;
	}

	/**
	 * Get the ID of the DirectoryStorageService holding the objects already processed by the DicomAnonymizer.
	 */
	public String getAnonymizerStorageID() {
		return anonymizerStorageID;
	}
	
	/**
	 * Get the ID of the DirectoryImportService holding the objects ready for export by the export pipeline.
	 */
	public String getExportInputID() {
		return exportInputID;
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
	
}