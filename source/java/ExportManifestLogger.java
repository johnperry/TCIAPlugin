package edu.uams.tcia;

import java.io.File;
import java.util.LinkedList;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.pipeline.AbstractPipelineStage;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.pipeline.Processor;
import org.rsna.ctp.plugin.Plugin;
import org.rsna.ctp.stdstages.ObjectCache;
import org.w3c.dom.Element;

/**
 * A Processor stage that passes DicomObjects to a ManifestLogPlugin.
 */
public class ManifestLogger extends AbstractPipelineStage implements Processor {

	static final Logger logger = Logger.getLogger(ManifestLogger.class);

	String manifestLogID;
	String cacheID;
	ManifestLogPlugin manifestLogPlugin;

	/**
	 * Construct the ManifestLogger PipelineStage.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the stage.
	 */
	public ManifestLogger(Element element) {
		super(element);
		manifestLogID = element.getAttribute("manifestLogID").trim();
		cacheID = element.getAttribute("cacheID").trim();
	}

	/**
	 * Start the pipeline stage. When this method is called, all the
	 * stages have been instantiated. We have to get the ObjectCache
	 * and AuditLog stages here to ensure that the Configuration
	 * has been instantiated. (Note: The Configuration constructor has
	 * not finished when the stages are constructed.)
	 */
	public void start() {
		Configuration config = Configuration.getInstance();
		Plugin plugin = config.getRegisteredPlugin(manifestLogID);
		if ((plugin != null) && (plugin instanceof ManifestLogPlugin)) {
			manifestLogPlugin = (ManifestLogPlugin)plugin;
		}
		else logger.warn(name+": manifestLogID \""+manifestLogID+"\" does not reference a ManifestLogPlugin");
	}

	/**
	 * Log objects as they are received by the stage.
	 * @param fileObject the object to log.
	 * @return the same FileObject.
	 */
	public FileObject process(FileObject fileObject) {
		lastFileIn = new File(fileObject.getFile().getAbsolutePath());
		lastTimeIn = System.currentTimeMillis();

		if ((manifestLogPlugin != null) && (fileObject instanceof DicomObject)) {
			
			//Get the cached object, if available
			DicomObject cachedDOB = null;
			ObjectCache cache = (ObjectCache)Configuration.getInstance().getRegisteredStage(cacheID);
			if (cache != null) cachedDOB = (DicomObject)cache.getCachedObject();

			//Make a DicomObject for the current object
			DicomObject dob = (DicomObject)fileObject;

			//Log it
			manifestLogPlugin.log(dob, cachedDOB);
		}

		lastFileOut = new File(fileObject.getFile().getAbsolutePath());
		lastTimeOut = System.currentTimeMillis();
		return fileObject;
	}

}