
/*===========================================================================
  Copyright (C) 2014 by the Okapi Framework contributors
-----------------------------------------------------------------------------
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
===========================================================================*/

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;

import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.Util;
import net.sf.okapi.common.filters.FilterConfigurationMapper;
import net.sf.okapi.common.filters.IFilterConfigurationMapper;
import net.sf.okapi.common.pipeline.IPipelineStep;
import net.sf.okapi.common.pipelinedriver.BatchItemContext;
import net.sf.okapi.common.pipelinedriver.IPipelineDriver;
import net.sf.okapi.common.pipelinedriver.PipelineDriver;
import net.sf.okapi.common.resource.RawDocument;
import net.sf.okapi.filters.openxml.OpenXMLFilter;
import net.sf.okapi.filters.rainbowkit.RainbowKitFilter;
import net.sf.okapi.lib.xliff2.core.Part.GetTarget;
import net.sf.okapi.lib.xliff2.core.Segment;
import net.sf.okapi.lib.xliff2.core.Unit;
import net.sf.okapi.lib.xliff2.processor.DefaultEventHandler;
import net.sf.okapi.lib.xliff2.processor.XLIFFProcessor;
import net.sf.okapi.lib.xliff2.reader.Event;
import net.sf.okapi.steps.common.RawDocumentToFilterEventsStep;
import net.sf.okapi.steps.rainbowkit.creation.ExtractionStep;
import net.sf.okapi.steps.rainbowkit.creation.Parameters;
import net.sf.okapi.steps.rainbowkit.postprocess.MergingStep;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class Main {

	protected static LocaleId locale_source = LocaleId.ENGLISH;
	protected static LocaleId locale_target = LocaleId.fromString("fr");
	protected static String root;
	protected static String action;
	protected static String encoding_input;
	protected static String encoding_output;
	protected static String filter;
	protected static String filter_config;
	protected static String file_path;

	public static void main(String[] args) throws URISyntaxException {

		// create Options object
		Options options = new Options();

		options.addOption("f", true, "input file path.");
		options.addOption("a", true, "Action. Turn on extract (e), merge (m), translate (t)");
		options.addOption("ie", false, "input encoding");
		options.addOption("oe", false, "output encoding");
		options.addOption("il", true, "input locale");
		options.addOption("ol", true, "output locale");
		options.addOption("fi", false, "filter");
		options.addOption("fc", false, "filter config");
		options.addOption("h", "help", false, "print this help");
		// URL inputUrl = Main.class.getResource("myDoc.docx");

		// create the command line parser
		CommandLineParser parser = new DefaultParser();

		try {
			// parse the command line arguments
			CommandLine cmd = parser.parse(options, args);
			file_path = cmd.getOptionValue("f");
			action = cmd.getOptionValue("a");
			encoding_input = cmd.getOptionValue("ie");
			encoding_output = cmd.getOptionValue("oe");
			// locale_source = LocaleId.fromString(cmd.getOptionValue("il"));
			// locale_target = LocaleId.fromString(cmd.getOptionValue("ol"));
			filter = cmd.getOptionValue("fi");
			filter_config = cmd.getOptionValue("fc");

			if (cmd.hasOption("h")) {
				printUsage(options);
				return;
			}

			System.out.println("file: " + file_path);

			File inputFile = new File(file_path);
			root = inputFile.getParent();

			if (action.equals("e")) {
				// Extract and XLIFF2 t-kit in a 'pack1' sub-directory in the directory of the
				// input file
				extract(inputFile);
			} else if (action.equals("t")) {
				// Make some change in the extracted file
				File xliffFile = new File(root + File.separator + "pack1" + File.separator + "work" + File.separator
						+ inputFile.getName() + ".xlf");
				modifyXLIFF(xliffFile);
			} else if (action.equals("m")) {
				// Merge the XLIFF2 file back
				// Result goes to the 'done' sub-directory of the 'pack1' directory
				File manifestFile = new File(root + File.separator + "pack1" + File.separator + "manifest.rkm");
				merge(manifestFile);
			}

			return;

		} catch (ParseException exp) {
			// oops, something went wrong
			System.err.println("Parsing failed.  Reason: " + exp.getMessage());
			printUsage(options);
			return;
		}

	}

	private static void printUsage(Options options) {
		final HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("java -jar okap--genesis-tools.jar", options);
	}

	private static void extract(File inputFile) {
		try {
			// Create the pipeline driver
			IPipelineDriver driver = createDriver(root);
			// Add the extraction step
			driver.addStep(new RawDocumentToFilterEventsStep());
			// Create and set up the t-kit creation step
			IPipelineStep extStep = new ExtractionStep();
			Parameters params = (Parameters) extStep.getParameters();
			params.setWriterClass("net.sf.okapi.steps.rainbowkit.xliff.XLIFF2PackageWriter");
			// Add the t-kit creation step
			driver.addStep(extStep);

			// Add the input file to the driver
			RawDocument rawDoc = new RawDocument(inputFile.toURI(), "UTF-8", locale_source, locale_target,
					"okf_openxml");
			// Set the output information (it goes in the manifest)
			String path = inputFile.getAbsolutePath();
			String outputPath = Util.getDirectoryName(path) + File.separator + Util.getFilename(path, false) + ".out"
					+ Util.getExtension(path);
			File outputFile = new File(outputPath);
			// Create the batch item to process and add it to the driver
			BatchItemContext item = new BatchItemContext(rawDoc, outputFile.toURI(), "UTF-8");
			driver.addBatchItem(item);
			// Run the pipeline
			driver.processBatch();
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private static void modifyXLIFF(File file) {
		try {
			// Create a processor that add some text at the end
			// of each non-empty segment
			XLIFFProcessor proc = new XLIFFProcessor();
			proc.add(new DefaultEventHandler() {
				@Override
				public Event handleUnit(Event event) {
					Unit unit = event.getUnit();
					for (Segment segment : unit.getSegments()) {
						if (segment.getSource().isEmpty())
							continue;
						segment.getTarget(GetTarget.CLONE_SOURCE).append(" blah blah...");
					}
					return event;
				}
			});
			// Run the processor (read and write)
			File tmpFile = new File(file.getAbsolutePath() + ".tmp");
			proc.run(file, tmpFile);
			file.delete();
			tmpFile.renameTo(file);
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private static void merge(File manifestFile) {
		try {
			IPipelineDriver driver = createDriver(root);
			// Add the extraction step
			driver.addStep(new RawDocumentToFilterEventsStep());
			// Add the t-kit merging step
			driver.addStep(new MergingStep());

			// Add the input file (manifest file) to the driver
			RawDocument rawDoc = new RawDocument(manifestFile.toURI(), "UTF-8", locale_source, locale_target,
					"okf_rainbowkit-noprompt");
			driver.addBatchItem(rawDoc);
			// Run the pipeline
			driver.processBatch();
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	static IPipelineDriver createDriver(String root) {
		// Create the pipeline driver
		IPipelineDriver driver = new PipelineDriver();
		// Create a filter configuration map
		IFilterConfigurationMapper fcMapper = new FilterConfigurationMapper();
		fcMapper.addConfigurations(OpenXMLFilter.class.getName());
		fcMapper.addConfigurations(RainbowKitFilter.class.getName());
		// Set the filter configuration map to use with the driver
		driver.setFilterConfigurationMapper(fcMapper);
		// Set the root folder for the driver's context
		driver.setRootDirectories(root, root);
		driver.setOutputDirectory(root);
		return driver;
	}

}
