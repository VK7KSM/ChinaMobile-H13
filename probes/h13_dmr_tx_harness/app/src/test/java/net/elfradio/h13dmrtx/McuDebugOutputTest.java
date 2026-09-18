package net.elfradio.h13dmrtx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class McuDebugOutputTest {
    @Test
    public void enableAndDisableUseHistoricalCommands() throws Exception {
        List<String> commands = new ArrayList<>();
        McuDebugOutput output = new McuDebugOutput((command, timeoutMs) -> {
            commands.add(command);
            return new byte[0];
        });
        assertTrue(output.restored());
        assertFalse(output.enabled());
        output.enable();
        assertFalse(output.restored());
        assertTrue(output.enabled());
        output.disable();
        assertTrue(output.restored());
        assertFalse(output.enabled());
        assertEquals("[print 1, print 0]", commands.toString());
    }

    @Test
    public void failedEnableStillRequiresDisable() {
        McuDebugOutput output = new McuDebugOutput((command, timeoutMs) -> {
            throw new IOException("证据保存失败");
        });
        assertThrows(IOException.class, output::enable);
        assertFalse(output.restored());
        assertTrue(output.enabled());
    }

    @Test
    public void failedDisableDoesNotClaimRestored() throws Exception {
        List<String> commands = new ArrayList<>();
        McuDebugOutput output = new McuDebugOutput((command, timeoutMs) -> {
            commands.add(command);
            if ("print 0".equals(command)) {
                throw new IOException("写出失败");
            }
            return new byte[0];
        });
        output.enable();
        assertThrows(IOException.class, output::disable);
        assertFalse(output.restored());
        assertTrue(output.enabled());
        assertEquals("[print 1, print 0]", commands.toString());
    }
}
