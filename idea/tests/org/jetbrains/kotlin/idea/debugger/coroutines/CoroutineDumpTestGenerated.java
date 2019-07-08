/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutines;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.TestsPackage}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("idea/testData/debugger/tinyApp/src/coroutines")
@TestDataPath("$PROJECT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public class CoroutineDumpTestGenerated extends AbstractCoroutineDumpTest {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, TargetBackend.ANY, testDataFilePath);
    }

    public void testAllFilesPresentInCoroutines() throws Exception {
        KotlinTestUtils.assertAllTestsPresentByMetadata(this.getClass(), new File("idea/testData/debugger/tinyApp/src/coroutines"), Pattern.compile("^(.+)\\.(kt|kts)$"), TargetBackend.ANY, true);
    }

    @TestMetadata("noCoroutines.kt")
    public void testNoCoroutines() throws Exception {
        runTest("idea/testData/debugger/tinyApp/src/coroutines/noCoroutines.kt");
    }

    @TestMetadata("threeCoroutines.kt")
    public void testThreeCoroutines() throws Exception {
        runTest("idea/testData/debugger/tinyApp/src/coroutines/threeCoroutines.kt");
    }

    @TestMetadata("twoDumps.kt")
    public void testTwoDumps() throws Exception {
        runTest("idea/testData/debugger/tinyApp/src/coroutines/twoDumps.kt");
    }
}
