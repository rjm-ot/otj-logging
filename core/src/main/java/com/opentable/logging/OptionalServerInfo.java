/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.opentable.logging;

import java.util.Objects;
import java.util.UUID;

import com.opentable.serverinfo.ServerInfo;

class OptionalServerInfo
{
    @FunctionalInterface
    interface WarningReporter
    {
        void warn(String message, Throwable t);
    }

    static String getDefaultClientName(WarningReporter reporter)
    {
        try {
            return Objects.toString(ServerInfo.get(ServerInfo.SERVER_TOKEN), UUID.randomUUID().toString());
        } catch (Exception e) {
            reporter.warn("No client name was set on appender!  Failed to get default value from otj-serverinfo.", e);
            return null;
        }
    }
}