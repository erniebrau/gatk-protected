package org.broadinstitute.sting.utils;

import org.broadinstitute.sting.BaseTest;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Assert;

import java.io.File;

public class PathUtilsUnitTest extends BaseTest {
    @BeforeClass
    public static void init() { }

    /**
     * Tests that we can successfully refresh a volume
     */
    @Test
    public void testRefreshVolume() {
        logger.warn("Executing testRefreshVolume");

        Assert.assertTrue(successfullyRefreshedVolume(System.getProperty("java.io.tmpdir")));
        Assert.assertFalse(successfullyRefreshedVolume("/a/made/up/file.txt"));
    }

    private boolean successfullyRefreshedVolume(String filename) {
        boolean result = true;

        try {
            PathUtils.refreshVolume(new File(filename));
        } catch (GATKException e) {
            result = false;
        }

        logger.warn(filename + " is accessible : " + result);

        return result;
    }
}
