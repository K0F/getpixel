package org.getpixel.magnifier;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** JUnit bridge: Gradle's testDebugUnitTest runs the JUnit-free logic checks. */
public final class LogicTestSuite {

    @Test
    public void pureLogic() {
        assertEquals("pure logic tests", 0, PureLogicTest.runAll());
    }
}