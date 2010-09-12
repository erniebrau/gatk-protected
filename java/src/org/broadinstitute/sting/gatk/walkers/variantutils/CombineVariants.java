/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.variantutils;

import org.broad.tribble.util.variantcontext.VariantContext;
import org.broad.tribble.vcf.*;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.contexts.variantcontext.VariantContextUtils;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.Reference;
import org.broadinstitute.sting.gatk.walkers.Requires;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.gatk.walkers.Window;
import org.broadinstitute.sting.gatk.walkers.annotator.VariantAnnotatorEngine;
import org.broadinstitute.sting.utils.SampleUtils;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.vcf.VCFUtils;

import java.util.*;

/**
 * Combines VCF records from different sources; supports both full merges and set unions.
 * Merge: combines multiple records into a single one; if sample names overlap then they are uniquified.
 * Union: assumes each rod represents the same set of samples (although this is not enforced); using the
 *   priority list (if provided), emits a single record instance at every position represented in the rods.
 */
@Reference(window=@Window(start=-50,stop=50))
@Requires(value={})
public class CombineVariants extends RodWalker<Integer, Integer> {

    @Output(doc="File to which variants should be written",required=true)
    protected VCFWriter vcfWriter = null;

    // the types of combinations we currently allow
    @Argument(shortName="genotypeMergeOptions", doc="How should we merge genotype records for samples shared across the ROD files?", required=false)
    public VariantContextUtils.GenotypeMergeType genotypeMergeOption = VariantContextUtils.GenotypeMergeType.PRIORITIZE;

    @Argument(shortName="variantMergeOptions", doc="How should we merge variant records across RODs?  Union leaves the record if any record is unfiltered, Intersection requires all records to be unfiltered", required=false)
    public VariantContextUtils.VariantMergeType variantMergeOption = VariantContextUtils.VariantMergeType.UNION;

    @Argument(fullName="rod_priority_list", shortName="priority", doc="When taking the union of variants containing genotypes: a comma-separated string describing the priority ordering for the genotypes as far as which record gets emitted; a complete priority list MUST be provided", required=false)
    public String PRIORITY_STRING = null;

    @Argument(fullName="printComplexMerges", shortName="printComplexMerges", doc="Print out interesting sites requiring complex compatibility merging", required=false)
    public boolean printComplexMerges = false;

    @Argument(fullName="filteredAreUncalled", shortName="filteredAreUncalled", doc="If true, then filtered VCFs are treated as uncalled, so that filtered set annotation don't appear in the combined VCF", required=false)
    public boolean filteredAreUncalled = false;

    @Argument(fullName="minimalVCF", shortName="minimalVCF", doc="If true, then the output VCF will contain no INFO or genotype INFO field", required=false)
    public boolean minimalVCF = false;

    @Argument(fullName="setKey", shortName="setKey", doc="Key, by default set, in the INFO key=value tag emitted describing which set the combined VCF record came from.  Set to null if you don't want the set field emitted.", required=false)
    public String SET_KEY = "set";

    private List<String> priority = null;

    private VariantAnnotatorEngine engine;

    public void initialize() {
        validateAnnotateUnionArguments();

        Map<String, VCFHeader> vcfRods = VCFUtils.getVCFHeadersFromRods(getToolkit(), null);
        Set<String> samples = SampleUtils.getSampleList(vcfRods, genotypeMergeOption);

        ArrayList<String> annotationClassesToUse = new ArrayList<String>();
        annotationClassesToUse.add("Standard");
        engine = new VariantAnnotatorEngine(getToolkit(), annotationClassesToUse, new ArrayList<String>());

        if ( SET_KEY.toLowerCase().equals("null") )
            SET_KEY = null;

        Set<VCFHeaderLine> headerLines = VCFUtils.smartMergeHeaders(vcfRods.values(), logger);
        headerLines.add(new VCFHeaderLine("source", "CombineVariants"));
        if ( SET_KEY != null )
            headerLines.add(new VCFInfoHeaderLine(SET_KEY, 1, VCFHeaderLineType.String, "Source VCF for the merged record in CombineVariants"));
        vcfWriter.writeHeader(new VCFHeader(headerLines, samples));
    }

    private void validateAnnotateUnionArguments() {
        Set<String> rodNames = SampleUtils.getRodNamesWithVCFHeader(getToolkit(), null);

        if ( genotypeMergeOption == VariantContextUtils.GenotypeMergeType.PRIORITIZE && PRIORITY_STRING == null )
            throw new UserException.MissingArgument("rod_priority_list", "Priority string must be provided if you want to prioritize genotypes");

        if ( genotypeMergeOption == VariantContextUtils.GenotypeMergeType.PRIORITIZE )
            priority = new ArrayList<String>(Arrays.asList(PRIORITY_STRING.split(",")));
        else
            priority = new ArrayList<String>(rodNames);

        if ( rodNames.size() != priority.size() )
            throw new UserException.BadArgumentValue("rod_priority_list", "The priority list must contain exactly one rod binding per ROD provided to the GATK: rodNames=" + rodNames + " priority=" + priority);

        if ( ! rodNames.containsAll(priority) )
            throw new UserException.BadArgumentValue("rod_priority_list", "Not all priority elements provided as input RODs: " + PRIORITY_STRING);
    }

    public Integer map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if ( tracker == null ) // RodWalkers can make funky map calls
            return 0;

        // get all of the vcf rods at this locus
        // Need to provide reference bases to simpleMerge starting at current locus
        Collection<VariantContext> vcs = tracker.getAllVariantContexts(ref, context.getLocation());
        VariantContext mergedVC = VariantContextUtils.simpleMerge(vcs, priority, variantMergeOption,
                genotypeMergeOption, true, printComplexMerges, ref.getBase(), SET_KEY, filteredAreUncalled);


        //out.printf("   merged => %s%nannotated => %s%n", mergedVC, annotatedMergedVC);

        if ( mergedVC != null ) { // only operate at the start of events
            VariantContext annotatedMergedVC = engine.annotateContext(tracker, ref, mergedVC);
            if ( minimalVCF )
                annotatedMergedVC = VariantContextUtils.pruneVariantContext(annotatedMergedVC, new HashSet(Arrays.asList(SET_KEY)));
            vcfWriter.add(annotatedMergedVC, ref.getBase());
        }

        return vcs.isEmpty() ? 0 : 1;
    }

    public Integer reduceInit() {
        return 0;
    }

    public Integer reduce(Integer counter, Integer sum) {
        return counter + sum;
    }

    public void onTraversalDone(Integer sum) {}
}
