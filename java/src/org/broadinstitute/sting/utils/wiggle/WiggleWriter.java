package org.broadinstitute.sting.utils.wiggle;

import org.broadinstitute.sting.utils.GATKException;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.exceptions.UserException;

import java.io.*;

/**
 * Manages the output of wiggle files. Due to the wiggle spec (each wiggle file must be one chromosome), this writer
 * will throw exceptions (or output multiple files?)
 *
 * todo -- currently no support for fixed step (special case of variable step)
 * todo -- currently no support for span, start, or step
 *
 * @Author chartl
 * @Date Jul 21, 2010
 */
public class WiggleWriter {

    enum StepType {
        fixed("fixedStep"),variable("variableStep");

        String repr;

        StepType(String repr) {
            this.repr = repr;
        }

        public String toString() {
            return repr;
        }
    }

    private WiggleHeader wHeader = null;
    // the header that we need to write prior to the file; and on future files (if multiple outputs ??)
    private BufferedWriter wWriter = null;
    // the file to which we are writing
    private GenomeLoc firstLoc = null;
    // the first genome loc the writer saw; need to cache this to compare contigs to preserve spec
    private StepType type = StepType.variable;
    // the type of step for the wiggle file, todo -- allow this to change

    private String myFile = "unknown";

    public WiggleWriter(File outputFile) {
        myFile = outputFile.getAbsolutePath();
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(outputFile);
        } catch ( FileNotFoundException e ) {
            throw new UserException.CouldNotCreateOutputFile(outputFile, "Unable to create a wiggle file ", e);
        }

        wWriter = new BufferedWriter(new OutputStreamWriter(outputStream));
    }

    public WiggleWriter(OutputStream out) {
       wWriter = new BufferedWriter(new OutputStreamWriter(out)); 
    }

    public void writeHeader(WiggleHeader header) {
        wHeader = header;
        write(wWriter,header.toString());
    }

    public void writeData(GenomeLoc loc, Object dataPoint) {
        if ( this.firstLoc == null ) {
            firstLoc = loc;
            write(wWriter,String.format("%n"));
            write(wWriter,String.format("%s\tchrom=%s",type.toString(),firstLoc.getContig()));
            write(wWriter,String.format("%n"));
            write(wWriter,String.format("%d\t%s",loc.getStart(),dataPoint.toString()));
        } else if ( loc.compareContigs(firstLoc) == 0 ) {
            write(wWriter,String.format("%n"));
            write(wWriter,String.format("%d\t%s",loc.getStart(),dataPoint.toString()));
        } else {
            // todo -- maybe allow this to open a new file for the new chromosome?
            throw new GATKException("Attempting to write multiple contigs into wiggle file, first contig was "+firstLoc.getContig()+" most recent "+loc.getContig());
        }
    }

    private void write(BufferedWriter w, String s) {
        try {
            w.write(s);
            w.flush();
            // flush required so writing to output stream will work
        } catch (IOException e) {
            throw new UserException.CouldNotCreateOutputFile(myFile, String.format("Error writing the wiggle line %s", s), e);
        }
    }
}
