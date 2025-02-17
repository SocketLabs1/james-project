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

package org.apache.james.transport.matchers.utils;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import javax.mail.internet.AddressException;

import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;


public class MailAddressCollectionReader {

    public static Set<Optional<MailAddress>> read(String condition) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(condition));
        return Splitter.onPattern("(,| |\t)").splitToList(condition)
            .stream()
            .filter(Predicate.not(Strings::isNullOrEmpty))
            .map(MailAddressCollectionReader::getMailAddress)
            .collect(ImmutableSet.toImmutableSet());
    }

    private static Optional<MailAddress> getMailAddress(String s) {
        try {
            if (s.equals(MailAddress.NULL_SENDER_AS_STRING)) {
                return Optional.empty();
            }
            return Optional.of(new MailAddress(s));
        } catch (AddressException e) {
            throw new RuntimeException(e);
        }
    }

}
