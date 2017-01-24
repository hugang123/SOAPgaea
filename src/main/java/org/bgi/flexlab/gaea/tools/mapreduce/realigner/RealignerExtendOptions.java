package org.bgi.flexlab.gaea.tools.mapreduce.realigner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.bgi.flexlab.gaea.data.mapreduce.options.HadoopOptions;
import org.bgi.flexlab.gaea.data.options.GaeaOptions;

public class RealignerExtendOptions extends GaeaOptions implements HadoopOptions {
	public final static String SOFTWARE_NAME = "Realigner";
	public final static String SOFTWARE_VERSION = "1.0";

	private RealignerOptions realignerOptions = new RealignerOptions();
	private RecalibratorOptions bqsrOptions = new RecalibratorOptions();
	
	private final static HashSet<String> SHARE_ARGUMENT = new HashSet<String>();
	static{
		SHARE_ARGUMENT.add("k");
		SHARE_ARGUMENT.add("w");
		SHARE_ARGUMENT.add("r");
	}

	private boolean realignment;
	private boolean recalibration;
	
	private String reportOutput = null;

	public RealignerExtendOptions() {
		addOption("q", "recalibrator", false, "only run base recalibrator");
		addOption("R", "realigment", false, "only run realiger");
		addOption("u", "algoBoth", false, "run realiger and recalibrator");

		initialize();
		FormatHelpInfo(SOFTWARE_NAME, SOFTWARE_VERSION);
	}
	
	public void initialize(){
		List<Option> rOptions = realignerOptions.getOptionList();
		List<Option> bOptions = bqsrOptions.getOptionList();
		
		for(Option opt : rOptions){
			addOption(opt);
		}
		
		for(Option opt : bOptions){
			if(SHARE_ARGUMENT.contains(opt.getOpt()))
				continue;
			addOption(opt);
		}
	}

	@Override
	public void setHadoopConf(String[] args, Configuration conf) {
		conf.setStrings("args", args);
	}

	@Override
	public void getOptionsFromHadoopConf(Configuration conf) {
		String[] args = conf.getStrings("args");
		this.parse(args);
	}

	@Override
	public void parse(String[] args) {
		ArrayList<String> realigner = new ArrayList<String>();
		ArrayList<String> bqsr = new ArrayList<String>();
		
		List<Option> realignerShortOptions = realignerOptions.getOptionList();
		List<Option> bqsrShortOptions = bqsrOptions.getOptionList();

		try {
			cmdLine = parser.parse(options, args);
		} catch (ParseException e) {
			System.err.println(e.getMessage());
			printHelpInfotmation(SOFTWARE_NAME);
			System.exit(1);
		}
		
		if(cmdLine.hasOption("u")){
			realignment = recalibration = true;
		}else{
			if(cmdLine.hasOption("q"))
				recalibration = true;
			if(cmdLine.hasOption("R"))
				realignment = true;
		}
		
		if(cmdLine.hasOption("o"))
			this.reportOutput = cmdLine.getOptionValue("o");
		
		if (!isValid()) {
			throw new RuntimeException("must set at least one algorithm!");
		}
		
		for(Option option : realignerShortOptions){
			String op = option.getOpt();
			if(cmdLine.hasOption(op)){
				realigner.add("-"+op);
				if(option.hasArg())
					realigner.add(cmdLine.getOptionValue(op));
			}
		}
		
		for(Option option : bqsrShortOptions){
			String op = option.getOpt();
			if(cmdLine.hasOption(op)){
				bqsr.add("-"+op);
				if(option.hasArg())
					bqsr.add(cmdLine.getOptionValue(op));
			}
		}
		
		realignerOptions.parse((String[])realigner.toArray(new String[realigner.size()]));
		if(recalibration)
			bqsrOptions.parse((String[])bqsr.toArray(new String[bqsr.size()]));
		
		realigner.clear();
		bqsr.clear();
	}

	private boolean isValid() {
		return realignment || recalibration;
	}

	public boolean isRealignment() {
		return this.realignment;
	}

	public boolean isRecalibration() {
		return this.recalibration;
	}

	public RealignerOptions getRealignerOptions() {
		return this.realignerOptions;
	}

	public RecalibratorOptions getBqsrOptions() {
		return this.bqsrOptions;
	}
	
	public String getReportOutput(){
		if(this.reportOutput.endsWith("/"))
			return this.reportOutput;
		else
			return this.reportOutput+"/";
	}
}