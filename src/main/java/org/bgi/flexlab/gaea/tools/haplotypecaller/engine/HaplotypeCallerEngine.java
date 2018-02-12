package org.bgi.flexlab.gaea.tools.haplotypecaller.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;

import org.bgi.flexlab.gaea.data.exception.UserException;
import org.bgi.flexlab.gaea.data.exception.UserException.BadArgumentValueException;
import org.bgi.flexlab.gaea.data.structure.bam.GaeaSamRecord;
import org.bgi.flexlab.gaea.data.structure.location.GenomeLocation;
import org.bgi.flexlab.gaea.data.structure.reference.ChromosomeInformationShare;
import org.bgi.flexlab.gaea.data.structure.variant.VariantCallContext;
import org.bgi.flexlab.gaea.tools.haplotypecaller.Haplotype;
import org.bgi.flexlab.gaea.tools.haplotypecaller.IndexedSampleList;
import org.bgi.flexlab.gaea.tools.haplotypecaller.ReadLikelihoods;
import org.bgi.flexlab.gaea.tools.haplotypecaller.ReferenceConfidenceMode;
import org.bgi.flexlab.gaea.tools.haplotypecaller.ReferenceConfidenceModel;
import org.bgi.flexlab.gaea.tools.haplotypecaller.SampleList;
import org.bgi.flexlab.gaea.tools.haplotypecaller.afcalculator.FixedAFCalculatorProvider;
import org.bgi.flexlab.gaea.tools.haplotypecaller.allele.IndexedAlleleList;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotation.RMSMappingQuality;
import org.bgi.flexlab.gaea.tools.haplotypecaller.annotation.StrandBiasBySample;
import org.bgi.flexlab.gaea.tools.haplotypecaller.argumentcollection.HaplotypeCallerArgumentCollection;
import org.bgi.flexlab.gaea.tools.haplotypecaller.argumentcollection.UnifiedArgumentCollection;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.ActivityProfileState;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.AssemblyRegion;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.AssemblyRegionTrimmer;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.AssemblyResultSet;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.ReadThreadingAssembler;
import org.bgi.flexlab.gaea.tools.haplotypecaller.pileup.AlignmentContext;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.MappingQualityReadFilter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.ReadFilter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.ReadFilterLibrary;
import org.bgi.flexlab.gaea.tools.haplotypecaller.readfilter.WellformedReadFilter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.smithwaterman.SmithWatermanAligner;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.AlleleBiasedDownsamplingUtils;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.AssemblyBasedCallerUtils;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.GenotypingGivenAllelesUtils;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.HomoSapiensConstants;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.RefMetaDataTracker;
import org.bgi.flexlab.gaea.tools.haplotypecaller.writer.GVCFWriter;
import org.bgi.flexlab.gaea.tools.haplotypecaller.writer.HaplotypeBAMWriter;
import org.bgi.flexlab.gaea.tools.jointcalling.UnifiedGenotypingEngine.GenotypingOutputMode;
import org.bgi.flexlab.gaea.tools.jointcalling.UnifiedGenotypingEngine.OutputMode;
import org.bgi.flexlab.gaea.tools.jointcalling.UnifiedGenotypingEngine;
import org.bgi.flexlab.gaea.tools.jointcalling.annotator.ChromosomeCounts;
import org.bgi.flexlab.gaea.tools.jointcalling.annotator.FisherStrand;
import org.bgi.flexlab.gaea.tools.jointcalling.annotator.QualByDepth;
import org.bgi.flexlab.gaea.tools.jointcalling.annotator.StrandOddsRatio;
import org.bgi.flexlab.gaea.tools.jointcalling.util.GaeaGvcfVariantContextUtils;
import org.bgi.flexlab.gaea.tools.jointcalling.util.GvcfMathUtils;
import org.bgi.flexlab.gaea.tools.vcfqualitycontrol2.util.GaeaVCFHeaderLines;
import org.bgi.flexlab.gaea.util.GaeaVCFConstants;
import org.bgi.flexlab.gaea.util.QualityUtils;
import org.bgi.flexlab.gaea.util.ReadUtils;
import org.bgi.flexlab.gaea.util.Utils;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;

public final class HaplotypeCallerEngine {

	private final HaplotypeCallerArgumentCollection hcArgs;

	private final SAMFileHeader readsHeader;

	private ReferenceConfidenceModel referenceConfidenceModel = null;

	private AssemblyRegionTrimmer trimmer = new AssemblyRegionTrimmer();

	// the genotyping engine for the isActive() determination
	private MinimalGenotypingEngine activeRegionEvaluationGenotyperEngine = null;

	private ReadThreadingAssembler assemblyEngine = null;

	private ReadLikelihoodCalculationEngine likelihoodCalculationEngine = null;

	private HaplotypeCallerGenotypingEngine genotypingEngine = null;

	private VariantAnnotatorEngine annotationEngine = null;

	// chromosome sequence
	private ChromosomeInformationShare referenceReader;

	// writes Haplotypes to a bam file when the -bamout option is specified
	private Optional<HaplotypeBAMWriter> haplotypeBAMWriter;
	
	private static final String ALLELE_VALUE = "ALLELE_VALUE";

	private Set<String> sampleSet;
	private SampleList samplesList;

	private byte minTailQuality;

	private SmithWatermanAligner aligner;

	public static final byte MIN_TAIL_QUALITY_WITH_ERROR_CORRECTION = 6;

	/**
	 * Minimum (exclusive) average number of high quality bases per soft-clip to
	 * consider that a set of soft-clips is a high quality set.
	 */
	private static final double AVERAGE_HQ_SOFTCLIPS_HQ_BASES_THRESHOLD = 6.0;

	/**
	 * Maximum-mininum confidence on a variant to exist to consider the position
	 * as a potential variant harbouring locus when looking for active regions.
	 */
	private static final double MAXMIN_CONFIDENCE_FOR_CONSIDERING_A_SITE_AS_POSSIBLE_VARIANT_IN_ACTIVE_REGION_DISCOVERY = 4.0;

	/**
	 * Minimum ploidy assumed when looking for loci that may harbour variation
	 * to identify active regions.
	 * <p>
	 * By default we take the putative ploidy provided by the user, but this
	 * turned out to be too insensitive for low ploidy, notoriously with haploid
	 * samples. Therefore we impose this minimum.
	 * </p>
	 */
	private static final int MINIMUM_PUTATIVE_PLOIDY_FOR_ACTIVE_REGION_DISCOVERY = 2;

	/**
	 * Reads with length lower than this number, after clipping off overhands
	 * outside the active region, won't be considered for genotyping.
	 */
	private static final int READ_LENGTH_FILTER_THRESHOLD = 10;

	/**
	 * Reads with mapping quality lower than this number won't be considered for
	 * genotyping, even if they have passed earlier filters (e.g. the walkers'
	 * own min MQ filter).
	 */
	private static final int READ_QUALITY_FILTER_THRESHOLD = 20;

	private static final List<VariantContext> NO_CALLS = Collections.emptyList();

	private static final Allele FAKE_REF_ALLELE = Allele.create("N", true);
	
	private static final Allele FAKE_ALT_ALLELE = Allele.create("<FAKE_ALT>", false); 

	/**
	 * Create and initialize a new HaplotypeCallerEngine given a collection of
	 * HaplotypeCaller arguments, a reads header, and a reference file
	 *
	 * @param hcArgs
	 *            command-line arguments for the HaplotypeCaller
	 * @param createBamOutIndex
	 *            true to create an index file for the bamout
	 * @param createBamOutMD5
	 *            true to create an md5 file for the bamout
	 * @param readsHeader
	 *            header for the reads
	 * @param referenceReader
	 *            reader to provide reference data
	 */
	public HaplotypeCallerEngine(final HaplotypeCallerArgumentCollection hcArgs, boolean createBamOutIndex,
			boolean createBamOutMD5, final SAMFileHeader readsHeader) {
		this(hcArgs, createBamOutIndex, createBamOutMD5, readsHeader, null);
	}

	public HaplotypeCallerEngine(final HaplotypeCallerArgumentCollection hcArgs, boolean createBamOutIndex,
			boolean createBamOutMD5, final SAMFileHeader readsHeader,
			VariantAnnotatorEngine annotationEngine) {
		this.hcArgs = Utils.nonNull(hcArgs);
		this.readsHeader = Utils.nonNull(readsHeader);
		this.annotationEngine = annotationEngine;
		this.aligner = SmithWatermanAligner.getAligner(hcArgs.smithWatermanImplementation);
		initialize(createBamOutIndex, createBamOutMD5);
	}

	private void initialize(boolean createBamOutIndex, final boolean createBamOutMD5) {
		// Note: order of operations matters here!

		initializeSamples();

		// Must be called after initializeSamples()
		validateAndInitializeArgs();
		minTailQuality = (byte) (hcArgs.minBaseQualityScore - 1);

		initializeActiveRegionEvaluationGenotyperEngine();

		if (annotationEngine == null) {
			annotationEngine = VariantAnnotatorEngine.ofSelectedMinusExcluded(
					hcArgs.variantAnnotationArgumentCollection, hcArgs.dbsnp, hcArgs.comps);

		}

		genotypingEngine = new HaplotypeCallerGenotypingEngine(hcArgs, samplesList,
				FixedAFCalculatorProvider.createThreadSafeProvider(hcArgs), !hcArgs.doNotRunPhysicalPhasing);
		genotypingEngine.setAnnotationEngine(annotationEngine);

		referenceConfidenceModel = new ReferenceConfidenceModel(samplesList, readsHeader,
				hcArgs.indelSizeToEliminateInRefModel);

		// Allele-specific annotations are not yet supported in the VCF mode
		if (isAlleleSpecificMode(annotationEngine) && isVCFMode()) {
			throw new UserException("Allele-specific annotations are not yet supported in the VCF mode");
		}

		haplotypeBAMWriter = AssemblyBasedCallerUtils.createBamWriter(hcArgs, createBamOutIndex, createBamOutMD5,
				readsHeader);
		assemblyEngine = AssemblyBasedCallerUtils.createReadThreadingAssembler(hcArgs);
		likelihoodCalculationEngine = AssemblyBasedCallerUtils.createLikelihoodCalculationEngine(hcArgs.likelihoodArgs);

		trimmer.initialize(hcArgs, readsHeader.getSequenceDictionary(), hcArgs.debug,
				hcArgs.genotypingOutputMode == GenotypingOutputMode.GENOTYPE_GIVEN_ALLELES, emitReferenceConfidence());
	}

	private boolean isVCFMode() {
		return hcArgs.emitReferenceConfidence == ReferenceConfidenceMode.NONE;
	}

	private boolean isAlleleSpecificMode(final VariantAnnotatorEngine annotationEngine) {
		// HACK. Note: we can't use subclass information from
		// ReducibleAnnotation (which would be the obvious choice)
		// because RMSMappingQuality is both a reducible annotation and a
		// standard annotation.

		return annotationEngine.getInfoAnnotations().stream()
				.anyMatch(infoFieldAnnotation -> infoFieldAnnotation.getClass().getSimpleName().startsWith("AS_"))
				|| annotationEngine.getGenotypeAnnotations().stream().anyMatch(
						genotypeAnnotation -> genotypeAnnotation.getClass().getSimpleName().startsWith("AS_"));
	}

	private void validateAndInitializeArgs() {
		if (hcArgs.samplePloidy != HomoSapiensConstants.DEFAULT_PLOIDY
				&& !hcArgs.doNotRunPhysicalPhasing) {
			hcArgs.doNotRunPhysicalPhasing = true;
		}

		if (hcArgs.dontGenotype && emitReferenceConfidence()) {
			throw new UserException("You cannot request gVCF output and 'do not genotype' at the same time");
		}

		if (emitReferenceConfidence()) {
			if (hcArgs.genotypingOutputMode == GenotypingOutputMode.GENOTYPE_GIVEN_ALLELES) {
				throw new BadArgumentValueException("ERC/gt_mode",
						"you cannot request reference confidence output and GENOTYPE_GIVEN_ALLELES at the same time");
			}

			hcArgs.STANDARD_CONFIDENCE_FOR_CALLING = -0.0;

			// also, we don't need to output several of the annotations
			hcArgs.variantAnnotationArgumentCollection.annotationsToExclude.add(ChromosomeCounts.class.getSimpleName());
			hcArgs.variantAnnotationArgumentCollection.annotationsToExclude.add(FisherStrand.class.getSimpleName());
			hcArgs.variantAnnotationArgumentCollection.annotationsToExclude.add(StrandOddsRatio.class.getSimpleName());
			hcArgs.variantAnnotationArgumentCollection.annotationsToExclude.add(QualByDepth.class.getSimpleName());

			// but we definitely want certain other ones
			hcArgs.variantAnnotationArgumentCollection.annotationsToUse.add(StrandBiasBySample.class.getSimpleName());
			hcArgs.annotateAllSitesWithPLs = true;
		} else if (!hcArgs.doNotRunPhysicalPhasing) {
			hcArgs.doNotRunPhysicalPhasing = true;
		}

		if (hcArgs.CONTAMINATION_FRACTION_FILE != null) {
			hcArgs.setSampleContamination(AlleleBiasedDownsamplingUtils.loadContaminationFile(
					hcArgs.CONTAMINATION_FRACTION_FILE, hcArgs.CONTAMINATION_FRACTION, sampleSet));
		}

		if (hcArgs.genotypingOutputMode == GenotypingOutputMode.GENOTYPE_GIVEN_ALLELES
				&& hcArgs.assemblerArgs.consensusMode) {
			throw new UserException(
					"HaplotypeCaller cannot be run in both GENOTYPE_GIVEN_ALLELES mode and in consensus mode at the same time. Please choose one or the other.");
		}

		Utils.validateArg(
				hcArgs.likelihoodArgs.BASE_QUALITY_SCORE_THRESHOLD >= QualityUtils.MINIMUM_USABLE_QUALITY_SCORE,
				"BASE_QUALITY_SCORE_THRESHOLD must be greater than or equal to "
						+ QualityUtils.MINIMUM_USABLE_QUALITY_SCORE + " (QualityUtils.MIN_USABLE_Q_SCORE)");

		if (emitReferenceConfidence() && samplesList.numberOfSamples() != 1) {
			throw new BadArgumentValueException("--emitRefConfidence",
					"Can only be used in single sample mode currently. Use the sample_name argument to run on a single sample out of a multi-sample BAM file.");
		}
	}

	private void initializeSamples() {
		sampleSet = ReadUtils.getSamplesFromHeader(readsHeader);
		samplesList = new IndexedSampleList(sampleSet);

		if (hcArgs.sampleNameToUse != null) {
			if (!sampleSet.contains(hcArgs.sampleNameToUse)) {
				throw new BadArgumentValueException("--sample_name",
						"Specified name does not exist in input bam files");
			}
			if (sampleSet.size() == 1) {
				// No reason to incur performance penalty associated with
				// filtering if they specified the name of the only sample
				hcArgs.sampleNameToUse = null;
			} else {
				samplesList = new IndexedSampleList(hcArgs.sampleNameToUse);
				sampleSet.clear();
				sampleSet.add(hcArgs.sampleNameToUse);
			}
		}
	}

	private void initializeActiveRegionEvaluationGenotyperEngine() {
		// create a UAC but with the exactCallsLog = null, so we only output the
		// log for the HC caller itself, if requested
		final UnifiedArgumentCollection simpleUAC = new UnifiedArgumentCollection();
		simpleUAC.copyStandardCallerArgsFrom(hcArgs);

		simpleUAC.outputMode = OutputMode.EMIT_VARIANTS_ONLY;
		simpleUAC.genotypingOutputMode = GenotypingOutputMode.DISCOVERY;
		simpleUAC.STANDARD_CONFIDENCE_FOR_CALLING = Math.min(
				MAXMIN_CONFIDENCE_FOR_CONSIDERING_A_SITE_AS_POSSIBLE_VARIANT_IN_ACTIVE_REGION_DISCOVERY,
				hcArgs.STANDARD_CONFIDENCE_FOR_CALLING); 
		
		simpleUAC.CONTAMINATION_FRACTION = 0.0;
		simpleUAC.CONTAMINATION_FRACTION_FILE = null;
		simpleUAC.exactCallsLog = null;
		// Seems that at least with some test data we can lose genuine haploid
		// variation if we use
		// UGs engine with ploidy == 1
		simpleUAC.samplePloidy = Math.max(MINIMUM_PUTATIVE_PLOIDY_FOR_ACTIVE_REGION_DISCOVERY,
				hcArgs.samplePloidy);

		activeRegionEvaluationGenotyperEngine = new MinimalGenotypingEngine(simpleUAC, samplesList,
				FixedAFCalculatorProvider.createThreadSafeProvider(simpleUAC));
	}

	/**
	 * @return the default set of read filters for use with the HaplotypeCaller
	 */
	public static List<ReadFilter> makeStandardHCReadFilters() {
		List<ReadFilter> filters = new ArrayList<>();
		filters.add(new MappingQualityReadFilter(READ_QUALITY_FILTER_THRESHOLD));
		filters.add(ReadFilterLibrary.MAPPING_QUALITY_AVAILABLE);
		filters.add(ReadFilterLibrary.MAPPED);
		filters.add(ReadFilterLibrary.NOT_SECONDARY_ALIGNMENT);
		filters.add(ReadFilterLibrary.NOT_DUPLICATE);
		filters.add(ReadFilterLibrary.PASSES_VENDOR_QUALITY_CHECK);
		filters.add(ReadFilterLibrary.NON_ZERO_REFERENCE_LENGTH_ALIGNMENT);
		filters.add(ReadFilterLibrary.GOOD_CIGAR);
		filters.add(new WellformedReadFilter());

		return filters;
	}

	/**
	 * Create a VCF or GVCF writer as appropriate, given our arguments
	 *
	 * @param outputVCF
	 *            location to which the vcf should be written
	 * @param readsDictionary
	 *            sequence dictionary for the reads
	 * @return a VCF or GVCF writer as appropriate, ready to use
	 */
	public VariantContextWriter makeVCFWriter(final String outputVCF, final SAMSequenceDictionary readsDictionary) {
		Utils.nonNull(outputVCF);
		Utils.nonNull(readsDictionary);

		VariantContextWriter writer = GaeaGvcfVariantContextUtils.createVCFWriter(new File(outputVCF), readsDictionary,
				false);

		if (hcArgs.emitReferenceConfidence == ReferenceConfidenceMode.GVCF) {
			try {
				writer = new GVCFWriter(writer, hcArgs.GVCFGQBands, hcArgs.samplePloidy);
			} catch (IllegalArgumentException e) {
				throw new BadArgumentValueException("GQBands", "are malformed: " + e.getMessage());
			}
		}

		return writer;
	}

	/**
	 * Create a VCF header.
	 *
	 * @param sequenceDictionary
	 *            sequence dictionary for the reads
	 * @return a VCF header
	 */
	public VCFHeader makeVCFHeader(final SAMSequenceDictionary sequenceDictionary,
			final Set<VCFHeaderLine> defaultToolHeaderLines) {
		final Set<VCFHeaderLine> headerInfo = new HashSet<>();
		headerInfo.addAll(defaultToolHeaderLines);

		headerInfo.addAll(genotypingEngine.getAppropriateVCFInfoHeaders());
		// all annotation fields from VariantAnnotatorEngine
		headerInfo.addAll(annotationEngine.getVCFAnnotationDescriptions(emitReferenceConfidence()));
		// all callers need to add these standard annotation header lines
		headerInfo.add(GaeaVCFHeaderLines.getInfoLine(GaeaVCFConstants.DOWNSAMPLED_KEY));
		headerInfo.add(GaeaVCFHeaderLines.getInfoLine(GaeaVCFConstants.MLE_ALLELE_COUNT_KEY));
		headerInfo.add(GaeaVCFHeaderLines.getInfoLine(GaeaVCFConstants.MLE_ALLELE_FREQUENCY_KEY));
		// all callers need to add these standard FORMAT field header lines
		VCFStandardHeaderLines.addStandardFormatLines(headerInfo, true, VCFConstants.GENOTYPE_KEY,
				VCFConstants.GENOTYPE_QUALITY_KEY, VCFConstants.DEPTH_KEY, VCFConstants.GENOTYPE_PL_KEY);

		if (!hcArgs.doNotRunPhysicalPhasing) {
			headerInfo.add(GaeaVCFHeaderLines.getFormatLine(GaeaVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY));
			headerInfo.add(GaeaVCFHeaderLines.getFormatLine(GaeaVCFConstants.HAPLOTYPE_CALLER_PHASING_GT_KEY));
		}

		// FILTER fields are added unconditionally as it's not always 100%
		// certain the circumstances
		// where the filters are used. For example, in emitting all sites the
		// lowQual field is used
		headerInfo.add(GaeaVCFHeaderLines.getFilterLine(GaeaVCFConstants.LOW_QUAL_FILTER_NAME));

		if (emitReferenceConfidence()) {
			headerInfo.addAll(referenceConfidenceModel.getVCFHeaderLines());
		}

		final VCFHeader vcfHeader = new VCFHeader(headerInfo, sampleSet);
		vcfHeader.setSequenceDictionary(sequenceDictionary);
		return vcfHeader;
	}

	/**
	 * Writes an appropriate VCF header, given our arguments, to the provided
	 * writer
	 *
	 * @param vcfWriter
	 *            writer to which the header should be written
	 */
	public void writeHeader(final VariantContextWriter vcfWriter, final SAMSequenceDictionary sequenceDictionary,
			final Set<VCFHeaderLine> defaultToolHeaderLines) {
		Utils.nonNull(vcfWriter);
		vcfWriter.writeHeader(makeVCFHeader(sequenceDictionary, defaultToolHeaderLines));
	}

	/**
	 * Given a pileup, returns an ActivityProfileState containing the
	 * probability (0.0 to 1.0) that it's an "active" site.
	 *
	 * Note that the current implementation will always return either 1.0 or
	 * 0.0, as it relies on the smoothing in
	 * {@link org.broadinstitute.hellbender.utils.activityprofile.BandPassActivityProfile}
	 * to create the full distribution of probabilities. This is consistent with
	 * GATK 3.
	 *
	 * @param context
	 *            reads pileup to examine
	 * @param ref
	 *            reference base overlapping the pileup locus
	 * @param features
	 *            features overlapping the pileup locus
	 * @return probability between 0.0 and 1.0 that the site is active (in
	 *         practice with this implementation: either 0.0 or 1.0)
	 */
	public ActivityProfileState isActive(final AlignmentContext context, final ChromosomeInformationShare ref,
			final RefMetaDataTracker features,GenomeLocation interval) {

		if (hcArgs.genotypingOutputMode == GenotypingOutputMode.GENOTYPE_GIVEN_ALLELES) {
			final VariantContext vcFromAllelesRod = GenotypingGivenAllelesUtils
					.composeGivenAllelesVariantContextFromRod(features,interval, false,
							hcArgs.alleles);
			if (vcFromAllelesRod != null) {
				return new ActivityProfileState(interval, 1.0);
			}
		}

		if (hcArgs.USE_ALLELES_TRIGGER) {
			return new ActivityProfileState(interval,
					RefMetaDataTracker.getValues(hcArgs.alleles, interval).size() > 0 ? 1.0 : 0.0);
		}

		if (context == null || context.getBasePileup().isEmpty()) {
			// if we don't have any data, just abort early
			return new ActivityProfileState(interval, 0.0);
		}

		final int ploidy = activeRegionEvaluationGenotyperEngine.getConfiguration().samplePloidy;
		final List<Allele> noCall = GaeaGvcfVariantContextUtils.noCallAlleles(ploidy); 

		final Map<String, AlignmentContext> splitContexts;
		if (samplesList.numberOfSamples() == 1) {
			// If we know a priori that there's just one sample, take a shortcut
			// and dont examine each read in the pileup
			splitContexts = context.splitContextBySampleName(samplesList.getSample(0), readsHeader);
		} else {
			splitContexts = context.splitContextBySampleName(readsHeader);
		}

		final GenotypesContext genotypes = GenotypesContext.create(splitContexts.keySet().size());
		final GvcfMathUtils.RunningAverage averageHQSoftClips = new GvcfMathUtils.RunningAverage();
		for (final Map.Entry<String, AlignmentContext> sample : splitContexts.entrySet()) {
			// The ploidy here is not dictated by the sample but by the simple
			// genotyping-engine used to determine whether regions are active or
			// not.
			final int activeRegionDetectionHackishSamplePloidy = activeRegionEvaluationGenotyperEngine
					.getConfiguration().samplePloidy;
			final double[] genotypeLikelihoods = referenceConfidenceModel.calcGenotypeLikelihoodsOfRefVsAny(
					activeRegionDetectionHackishSamplePloidy, sample.getValue().getBasePileup(), ref.getGA4GHBaseBytes(interval.getStart()-1)[0],
					hcArgs.minBaseQualityScore, averageHQSoftClips).getGenotypeLikelihoods();
			genotypes.add(new GenotypeBuilder(sample.getKey()).alleles(noCall).PL(genotypeLikelihoods).make());
		}

		final List<Allele> alleles = Arrays.asList(FAKE_REF_ALLELE, FAKE_ALT_ALLELE);
		final double isActiveProb;

		if (genotypes.size() == 1) {
			// Faster implementation avoiding the costly and over complicated
			// Exact AFCalculator machinery:
			// This is the case when doing GVCF output.
			isActiveProb = activeRegionEvaluationGenotyperEngine.calculateSingleSampleRefVsAnyActiveStateProfileValue(
					genotypes.get(0).getLikelihoods().getAsVector());
		} else {
			final VariantCallContext vcOut = activeRegionEvaluationGenotyperEngine.calculateGenotypes(
					new VariantContextBuilder("HCisActive!", context.getContig(), context.getLocation().getStart(),
							context.getLocation().getEnd(), alleles).genotypes(genotypes).make(),
					UnifiedGenotypingEngine.Model.SNP, readsHeader);
			isActiveProb = vcOut == null ? 0.0 : QualityUtils.qualToProb(vcOut.getPhredScaledQual());
		}
		return new ActivityProfileState(interval, isActiveProb,
				averageHQSoftClips.mean() > AVERAGE_HQ_SOFTCLIPS_HQ_BASES_THRESHOLD
						? ActivityProfileState.Type.HIGH_QUALITY_SOFT_CLIPS : ActivityProfileState.Type.NONE,
				averageHQSoftClips.mean());
	}

	/**
	 * Generate variant calls for an assembly region
	 *
	 * @param region
	 *            region to assemble and perform variant calling on
	 * @param features
	 *            Features overlapping the assembly region
	 * @return List of variants discovered in the region (may be empty)
	 */
	public List<VariantContext> callRegion(final AssemblyRegion region, final RefMetaDataTracker features) {
		if (hcArgs.justDetermineActiveRegions) {
			// we're benchmarking ART and/or the active region determination
			// code in the HC, just leave without doing any work
			return NO_CALLS;
		}

		if (hcArgs.sampleNameToUse != null) {
			removeReadsFromAllSamplesExcept(hcArgs.sampleNameToUse, region);
		}

		if (!region.isActive()) {
			// Not active so nothing to do!
			return referenceModelForNoVariation(region, true);
		}

		final List<VariantContext> givenAlleles = new ArrayList<>();
		if (hcArgs.genotypingOutputMode == GenotypingOutputMode.GENOTYPE_GIVEN_ALLELES) {
			features.getValues(ALLELE_VALUE).stream().filter(VariantContext::isNotFiltered)
					.forEach(givenAlleles::add);

			// No alleles found in this region so nothing to do!
			if (givenAlleles.isEmpty()) {
				return referenceModelForNoVariation(region, true);
			}
		} else if (region.size() == 0) {
			// No reads here so nothing to do!
			return referenceModelForNoVariation(region, true);
		}

		// run the local assembler, getting back a collection of information on
		// how we should proceed
		final AssemblyResultSet untrimmedAssemblyResult = AssemblyBasedCallerUtils.assembleReads(region, givenAlleles,
				hcArgs, readsHeader, samplesList, referenceReader, assemblyEngine, aligner);

		final SortedSet<VariantContext> allVariationEvents = untrimmedAssemblyResult.getVariationEvents();
		// TODO - line bellow might be unnecessary : it might be that
		// assemblyResult will always have those alleles anyway
		allVariationEvents.addAll(givenAlleles);

		final AssemblyRegionTrimmer.Result trimmingResult = trimmer.trim(region, allVariationEvents);

		if (!trimmingResult.isVariationPresent() && !hcArgs.disableOptimizations) {
			return referenceModelForNoVariation(region, false);
		}

		final AssemblyResultSet assemblyResult = trimmingResult.needsTrimming()
				? untrimmedAssemblyResult.trimTo(trimmingResult.getCallableRegion()) : untrimmedAssemblyResult;

		final AssemblyRegion regionForGenotyping = assemblyResult.getRegionForGenotyping();

		// filter out reads from genotyping which fail mapping quality based
		// criteria
		// TODO - why don't do this before any assembly is done? Why not just
		// once at the beginning of this method
		// TODO - on the originalActiveRegion?
		// TODO - if you move this up you might have to consider to change
		// referenceModelForNoVariation
		// TODO - that does also filter reads.
		final Collection<GaeaSamRecord> filteredReads = filterNonPassingReads(regionForGenotyping);
		final Map<String, List<GaeaSamRecord>> perSampleFilteredReadList = splitReadsBySample(filteredReads);

		// abort early if something is out of the acceptable range
		// TODO is this ever true at this point??? perhaps GGA. Need to check.
		if (!assemblyResult.isVariationPresent() && !hcArgs.disableOptimizations) {
			return referenceModelForNoVariation(region, false);
		}

		// For sure this is not true if gVCF is on.
		if (hcArgs.dontGenotype) {
			return NO_CALLS; // user requested we not proceed
		}

		// TODO is this ever true at this point??? perhaps GGA. Need to check.
		if (regionForGenotyping.size() == 0 && !hcArgs.disableOptimizations) {
			// no reads remain after filtering so nothing else to do!
			return referenceModelForNoVariation(region, false);
		}

		// evaluate each sample's reads against all haplotypes
		final List<Haplotype> haplotypes = assemblyResult.getHaplotypeList();
		final Map<String, List<GaeaSamRecord>> reads = splitReadsBySample(regionForGenotyping.getReads());

		// Calculate the likelihoods: CPU intensive part.
		final ReadLikelihoods<Haplotype> readLikelihoods = likelihoodCalculationEngine
				.computeReadLikelihoods(assemblyResult, samplesList, reads);

		// Realign reads to their best haplotype.
		final Map<GaeaSamRecord, GaeaSamRecord> readRealignments = AssemblyBasedCallerUtils
				.realignReadsToTheirBestHaplotype(readLikelihoods, assemblyResult.getReferenceHaplotype(),
						assemblyResult.getPaddedReferenceLoc(), aligner);
		readLikelihoods.changeReads(readRealignments);

		// Note: we used to subset down at this point to only the "best"
		// haplotypes in all samples for genotyping, but there
		// was a bad interaction between that selection and the marginalization
		// that happens over each event when computing
		// GLs. In particular, for samples that are heterozygous non-reference
		// (B/C) the marginalization for B treats the
		// haplotype containing C as reference (and vice versa). Now this is
		// fine if all possible haplotypes are included
		// in the genotyping, but we lose information if we select down to a few
		// haplotypes. [EB]

		final HaplotypeCallerGenotypingEngine.CalledHaplotypes calledHaplotypes = genotypingEngine
				.assignGenotypeLikelihoods(haplotypes, readLikelihoods, perSampleFilteredReadList,
						assemblyResult.getFullReferenceWithPadding(), assemblyResult.getPaddedReferenceLoc(),
						regionForGenotyping.getSpan(), features,
						(hcArgs.assemblerArgs.consensusMode ? Collections.<VariantContext>emptyList() : givenAlleles),
						emitReferenceConfidence(), readsHeader);

		if (haplotypeBAMWriter.isPresent()) {
			final Set<Haplotype> calledHaplotypeSet = new HashSet<>(calledHaplotypes.getCalledHaplotypes());
			if (hcArgs.disableOptimizations) {
				calledHaplotypeSet.add(assemblyResult.getReferenceHaplotype());
			}
			haplotypeBAMWriter.get().writeReadsAlignedToHaplotypes(haplotypes, assemblyResult.getPaddedReferenceLoc(),
					haplotypes, calledHaplotypeSet, readLikelihoods);
		}

		if (emitReferenceConfidence()) {
			if (!containsCalls(calledHaplotypes)) {
				// no called all of the potential haplotypes
				return referenceModelForNoVariation(region, false);
			} else {
				final List<VariantContext> result = new LinkedList<>();
				// output left-flanking non-variant section:
				if (trimmingResult.hasLeftFlankingRegion()) {
					result.addAll(referenceModelForNoVariation(trimmingResult.nonVariantLeftFlankRegion(), false));
				}
				// output variant containing region.
				result.addAll(referenceConfidenceModel.calculateRefConfidence(assemblyResult.getReferenceHaplotype(),
						calledHaplotypes.getCalledHaplotypes(), assemblyResult.getPaddedReferenceLoc(),
						regionForGenotyping, readLikelihoods, genotypingEngine.getPloidyModel(),
						calledHaplotypes.getCalls()));
				// output right-flanking non-variant section:
				if (trimmingResult.hasRightFlankingRegion()) {
					result.addAll(referenceModelForNoVariation(trimmingResult.nonVariantRightFlankRegion(), false));
				}
				return result;
			}
		} else {
			// TODO this should be updated once reducible annotations are
			// handled properly.
			return calledHaplotypes.getCalls().stream().map(RMSMappingQuality.getInstance()::finalizeRawMQ)
					.collect(Collectors.toList());
		}
	}

	private boolean containsCalls(final HaplotypeCallerGenotypingEngine.CalledHaplotypes calledHaplotypes) {
		return calledHaplotypes.getCalls().stream().flatMap(call -> call.getGenotypes().stream())
				.anyMatch(Genotype::isCalled);
	}

	/**
	 * Create an ref model result (ref model or no calls depending on mode) for
	 * an active region without any variation (not is active, or assembled to
	 * just ref)
	 *
	 * @param region
	 *            the region to return a no-variation result
	 * @param needsToBeFinalized
	 *            should the region be finalized before computing the ref model
	 *            (should be false if already done)
	 * @return a list of variant contexts (can be empty) to emit for this ref
	 *         region
	 */
	private List<VariantContext> referenceModelForNoVariation(final AssemblyRegion region,
			final boolean needsToBeFinalized) {
		if (emitReferenceConfidence()) {
			// TODO - why the activeRegion cannot manage its own one-time
			// finalization and filtering?
			// TODO - perhaps we can remove the last parameter of this method
			// and the three lines bellow?
			if (needsToBeFinalized) {
				finalizeRegion(region);
			}
			filterNonPassingReads(region);

			final GenomeLocation paddedLoc = region.getExtendedSpan();
			final Haplotype refHaplotype = AssemblyBasedCallerUtils.createReferenceHaplotype(region, paddedLoc,
					referenceReader);
			final List<Haplotype> haplotypes = Collections.singletonList(refHaplotype);
			return referenceConfidenceModel.calculateRefConfidence(refHaplotype, haplotypes, paddedLoc, region,
					createDummyStratifiedReadMap(refHaplotype, samplesList, region), genotypingEngine.getPloidyModel(),
					Collections.emptyList());
		} else {
			return NO_CALLS;
		}
	}

	/**
	 * Create a context that maps each read to the reference haplotype with
	 * log10 L of 0
	 * 
	 * @param refHaplotype
	 *            a non-null reference haplotype
	 * @param samples
	 *            a list of all samples
	 * @param region
	 *            the assembly region containing reads
	 * @return a map from sample -> PerReadAlleleLikelihoodMap that maps each
	 *         read to ref
	 */
	public ReadLikelihoods<Haplotype> createDummyStratifiedReadMap(final Haplotype refHaplotype,
			final SampleList samples, final AssemblyRegion region) {
		return new ReadLikelihoods<>(samples, new IndexedAlleleList<>(refHaplotype),
				splitReadsBySample(samples, region.getReads()));
	}

	/**
	 * Shutdown this HC engine, closing resources as appropriate
	 */
	public void shutdown() {
		likelihoodCalculationEngine.close();
		aligner.close();
		if (haplotypeBAMWriter.isPresent()) {
			haplotypeBAMWriter.get().close();
		}

	}

	private void finalizeRegion(final AssemblyRegion region) {
		AssemblyBasedCallerUtils.finalizeRegion(region, hcArgs.errorCorrectReads, hcArgs.dontUseSoftClippedBases,
				minTailQuality, readsHeader, samplesList);
	}

	private Set<GaeaSamRecord> filterNonPassingReads(final AssemblyRegion activeRegion) {
		// TODO: can we unify this additional filtering with
		// makeStandardHCReadFilter()?

		final Set<GaeaSamRecord> readsToRemove = new LinkedHashSet<>();
		for (final GaeaSamRecord rec : activeRegion.getReads()) {
			if (rec.getReadLength() < READ_LENGTH_FILTER_THRESHOLD
					|| rec.getMappingQuality() < READ_QUALITY_FILTER_THRESHOLD
					|| !ReadFilterLibrary.MATE_ON_SAME_CONTIG_OR_NO_MAPPED_MATE.test(rec)
					|| (hcArgs.keepRG != null && !rec.getReadGroup().equals(hcArgs.keepRG))) {
				readsToRemove.add(rec);
			}
		}
		activeRegion.removeAll(readsToRemove);
		return readsToRemove;
	}

	private Map<String, List<GaeaSamRecord>> splitReadsBySample(final Collection<GaeaSamRecord> reads) {
		return splitReadsBySample(samplesList, reads);
	}

	private Map<String, List<GaeaSamRecord>> splitReadsBySample(final SampleList samplesList,
			final Collection<GaeaSamRecord> reads) {
		return AssemblyBasedCallerUtils.splitReadsBySample(samplesList, readsHeader, reads);
	}

	/**
	 * Are we emitting a reference confidence in some form, or not?
	 *
	 * @return true if HC must emit reference confidence.
	 */
	public boolean emitReferenceConfidence() {
		return hcArgs.emitReferenceConfidence != ReferenceConfidenceMode.NONE;
	}

	private void removeReadsFromAllSamplesExcept(final String targetSample, final AssemblyRegion activeRegion) {
		final Set<GaeaSamRecord> readsToRemove = new LinkedHashSet<>();
		for (final GaeaSamRecord rec : activeRegion.getReads()) {
			if (!ReadUtils.getSampleName(rec, readsHeader).equals(targetSample)) {
				readsToRemove.add(rec);
			}
		}
		activeRegion.removeAll(readsToRemove);
	}
	
	public void setChromosome(ChromosomeInformationShare chr){
		this.referenceReader = chr;
	}
}
