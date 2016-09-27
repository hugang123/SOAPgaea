package org.bgi.flexlab.gaea.tools.annotator.effect;

import htsjdk.variant.variantcontext.VariantContext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.bgi.flexlab.gaea.tools.annotator.config.Config;
import org.bgi.flexlab.gaea.tools.annotator.interval.Variant;

/**
 * Annotate a VCF entry
 *
 */
public class VcfAnnotator implements Serializable{
	
	private static final long serialVersionUID = -6632995517658045554L;
	
	Config config;
	SnpEffectPredictor snpEffectPredictor;
	VariantContext variantContext;
	
	public VcfAnnotator(Config config){
		this.config = config;
		snpEffectPredictor = config.getSnpEffectPredictor();
	}
	
	/**
	 * Annotate a VCF entry
	 *
	 * @return true if the entry was annotated
	 */
	public boolean annotate(VcfAnnotationContext vac) {
		
		HashMap<String, AnnotationContext> annotationContexts = new HashMap<String, AnnotationContext>();
		
//		boolean filteredOut = false;
		//---
		// Analyze all changes in this VCF entry
		// Note, this is the standard analysis.
		//---
		List<Variant> variants = vac.variants(config.getGenome());
		for (Variant variant : variants) {

			// Calculate effects: By default do not annotate non-variant sites
			if (variant.isVariant()) {

				VariantEffects variantEffects = snpEffectPredictor.variantEffect(variant);
				AnnotationContext annotationContext = new AnnotationContext(variantEffects.get());
				annotationContexts.put(variant.getAlt(), annotationContext);
			}
		}
		if (annotationContexts.isEmpty()) {
			return false;
		}
		
		vac.setAnnotationContexts(annotationContexts);
		return true;
	}
	
	public List<String> convertAnnotationStrings(VcfAnnotationContext vac) {
		List<String> annoStrings = new ArrayList<String>();
		for (String alt : vac.getAlts()) {
			StringBuilder sb = new StringBuilder();
			sb.append(vac.getContig());
			sb.append("\t");
			sb.append(vac.getStart());
			sb.append("\t");
			sb.append(vac.getReference());
			sb.append("\t");
			sb.append(alt);
			AnnotationContext annoContext = vac.getAnnotationContext(alt);
			
			List<String> dbNameList = config.getDbNameList();
			for (String dbName : dbNameList) {
				
				String[] fields = config.getFieldsByDB(dbName);
				
				if (dbName.equalsIgnoreCase("GeneInfo")) {
					for (String field : fields) {
						sb.append("\t");
						sb.append(annoContext.getFieldByName(field));       
					}
				}else {
					for (String field : fields) {
						sb.append("\t");
						sb.append(annoContext.getAnnoItemAsString(field, "."));       
					}
				}
			}
			annoStrings.add(sb.toString());
		}
		return annoStrings;
	}
	
	public VariantContext convertAnnotationVcfline(VcfAnnotationContext vac) {
//		TODO
		
		return null;
	}

}