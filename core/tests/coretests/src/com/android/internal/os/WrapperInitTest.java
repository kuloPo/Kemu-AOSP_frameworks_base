/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WrapperInitTest {
    private Method mAppendNativeBridgeArgs;

    @Before
    public void setUp() throws Exception {
        mAppendNativeBridgeArgs = WrapperInit.class.getDeclaredMethod("appendNativeBridgeArgs",
                StringBuilder.class, String.class, String.class, String.class, String.class);
        mAppendNativeBridgeArgs.setAccessible(true);
    }

    private void appendNativeBridgeArgs(StringBuilder command, String instructionSet,
            String appDataDir, String runtimeInstructionSet, String nativeBridge) throws Throwable {
        try {
            mAppendNativeBridgeArgs.invoke(null, command, instructionSet, appDataDir,
                    runtimeInstructionSet, nativeBridge);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    @Test
    public void translatedAppPreservesMetadataAndQuotesShellArguments() throws Throwable {
        String path = "/data/user/10/com.example.'quoted $app";
        StringBuilder command = new StringBuilder("printf '%s\\n'");
        appendNativeBridgeArgs(command, "arm64", path, "x86_64", "libbridge.so");
        Process process = new ProcessBuilder("/system/bin/sh", "-c", command.toString()).start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor());
        assertEquals("-XX:NativeBridge=libbridge.so\n"
                + "-XX:NativeBridgeInstructionSet=arm64\n"
                + "-XX:NativeBridgeAppDataDir=" + path + "\n", output);
    }

    @Test
    public void hostAppAndDisabledBridgeDoNotAddOptions() throws Throwable {
        for (String library : new String[] {"", "0", "libbridge.so"}) {
            StringBuilder command = new StringBuilder();
            appendNativeBridgeArgs(command, "x86_64", null, "x86_64", library);
            assertEquals("", command.toString());
        }
        for (String library : new String[] {"", "0"}) {
            StringBuilder command = new StringBuilder();
            appendNativeBridgeArgs(command, "arm64", null, "x86_64", library);
            assertEquals("", command.toString());
        }
        StringBuilder command = new StringBuilder();
        appendNativeBridgeArgs(command, null, null, "x86_64", "libbridge.so");
        assertEquals("", command.toString());
    }

    @Test
    public void translatedAppRequiresDataDirectory() {
        for (String path : new String[] {null, "", ".", "relative/path", "/", "/data/user/0/pkg/"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> appendNativeBridgeArgs(new StringBuilder(), "arm64", path,
                            "x86_64", "libbridge.so"));
        }
    }
}
