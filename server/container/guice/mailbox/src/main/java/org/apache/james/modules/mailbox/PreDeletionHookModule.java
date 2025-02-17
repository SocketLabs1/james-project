/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.modules.mailbox;

import java.util.Set;

import javax.inject.Singleton;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.mailbox.extension.PreDeletionHook;
import org.apache.james.server.core.configuration.ConfigurationProvider;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;

public class PreDeletionHookModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(PreDeletionHookLoader.class).to(PreDeletionHookLoaderImpl.class);
    }

    @Provides
    @Singleton
    PreDeletionHooksConfiguration providesConfiguration(ConfigurationProvider configurationProvider) throws ConfigurationException {
        return PreDeletionHooksConfiguration.from(configurationProvider.getConfiguration("listeners"));
    }

    @Provides
    @Singleton
    Set<PreDeletionHook> createHooks(PreDeletionHooksConfiguration configuration, PreDeletionHookLoader loader) throws ClassNotFoundException {
        return configuration.getHooksConfiguration()
            .stream()
            .map(Throwing.function(loader::createHook).sneakyThrow())
            .collect(ImmutableSet.toImmutableSet());
    }
}
