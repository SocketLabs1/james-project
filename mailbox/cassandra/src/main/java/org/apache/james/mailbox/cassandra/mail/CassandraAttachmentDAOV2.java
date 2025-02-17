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

package org.apache.james.mailbox.cassandra.mail;

import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.datastax.driver.core.querybuilder.QueryBuilder.select;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.BLOB_ID;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.FIELDS;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.ID;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.ID_AS_UUID;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.MESSAGE_ID;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.SIZE;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.TABLE_NAME;
import static org.apache.james.mailbox.cassandra.table.CassandraAttachmentV2Table.TYPE;

import java.util.Objects;
import java.util.Optional;

import javax.inject.Inject;

import org.apache.james.backends.cassandra.init.configuration.CassandraConsistenciesConfiguration;
import org.apache.james.backends.cassandra.utils.CassandraAsyncExecutor;
import org.apache.james.blob.api.BlobId;
import org.apache.james.mailbox.cassandra.ids.CassandraMessageId;
import org.apache.james.mailbox.model.AttachmentId;
import org.apache.james.mailbox.model.AttachmentMetadata;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mailbox.model.MessageId;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.google.common.base.Preconditions;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CassandraAttachmentDAOV2 {
    public static class DAOAttachment {
        private final MessageId messageId;
        private final AttachmentId attachmentId;
        private final BlobId blobId;
        private final ContentType type;
        private final long size;

        DAOAttachment(MessageId messageId, AttachmentId attachmentId, BlobId blobId, ContentType type, long size) {
            this.messageId = messageId;
            this.attachmentId = attachmentId;
            this.blobId = blobId;
            this.type = type;
            this.size = size;
        }

        public AttachmentId getAttachmentId() {
            return attachmentId;
        }

        public BlobId getBlobId() {
            return blobId;
        }

        public ContentType getType() {
            return type;
        }

        public long getSize() {
            return size;
        }

        public MessageId getMessageId() {
            return messageId;
        }

        public AttachmentMetadata toAttachment() {
            return AttachmentMetadata.builder()
                .attachmentId(attachmentId)
                .type(type)
                .size(size)
                .messageId(messageId)
                .build();
        }

        @Override
        public final boolean equals(Object o) {
            if (o instanceof DAOAttachment) {
                DAOAttachment that = (DAOAttachment) o;

                return Objects.equals(this.size, that.size)
                    && Objects.equals(this.attachmentId, that.attachmentId)
                    && Objects.equals(this.blobId, that.blobId)
                    && Objects.equals(this.messageId, that.messageId)
                    && Objects.equals(this.type, that.type);
            }
            return false;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(attachmentId, blobId, type, size, messageId);
        }
    }

    public static DAOAttachment from(AttachmentMetadata attachment, BlobId blobId) {
        return new DAOAttachment(
            attachment.getMessageId(),
            attachment.getAttachmentId(),
            blobId,
            attachment.getType(),
            attachment.getSize());
    }

    private static Mono<DAOAttachment> fromRow(Row row, BlobId.Factory blobIfFactory, Mono<CassandraMessageId> fallback) {
        return Optional.ofNullable(row.getUUID(MESSAGE_ID))
            .map(CassandraMessageId.Factory::of)
            .map(Mono::just)
            .orElse(fallback)
            .map(messageIdAsUUid -> new DAOAttachment(
                messageIdAsUUid,
                AttachmentId.from(row.getString(ID)),
                blobIfFactory.from(row.getString(BLOB_ID)),
                ContentType.of(row.getString(TYPE)),
                row.getLong(SIZE)));
    }

    private final BlobId.Factory blobIdFactory;
    private final CassandraAsyncExecutor cassandraAsyncExecutor;
    private final PreparedStatement insertStatement;
    private final PreparedStatement insertMessageIdStatement;
    private final PreparedStatement deleteStatement;
    private final PreparedStatement selectStatement;
    private final PreparedStatement listBlobs;
    private final ConsistencyLevel consistencyLevel;

    @Inject
    public CassandraAttachmentDAOV2(BlobId.Factory blobIdFactory, Session session,
                                    CassandraConsistenciesConfiguration consistenciesConfiguration) {
        this.blobIdFactory = blobIdFactory;
        this.cassandraAsyncExecutor = new CassandraAsyncExecutor(session);
        this.consistencyLevel = consistenciesConfiguration.getRegular();

        this.selectStatement = prepareSelect(session);
        this.insertStatement = prepareInsert(session);
        this.deleteStatement = prepareDelete(session);
        this.listBlobs = prepareSelectBlobs(session);
        this.insertMessageIdStatement = prepareInsertMessageId(session);
    }

    private PreparedStatement prepareSelectBlobs(Session session) {
        return session.prepare(select(BLOB_ID)
            .from(TABLE_NAME));
    }

    private PreparedStatement prepareDelete(Session session) {
        return session.prepare(
            QueryBuilder.delete().from(TABLE_NAME)
                .where(eq(ID_AS_UUID, bindMarker(ID_AS_UUID))));
    }

    private PreparedStatement prepareInsert(Session session) {
        return session.prepare(
            insertInto(TABLE_NAME)
                .value(ID_AS_UUID, bindMarker(ID_AS_UUID))
                .value(ID, bindMarker(ID))
                .value(BLOB_ID, bindMarker(BLOB_ID))
                .value(TYPE, bindMarker(TYPE))
                .value(MESSAGE_ID, bindMarker(MESSAGE_ID))
                .value(SIZE, bindMarker(SIZE)));
    }

    private PreparedStatement prepareInsertMessageId(Session session) {
        return session.prepare(
            insertInto(TABLE_NAME)
                .value(ID_AS_UUID, bindMarker(ID_AS_UUID))
                .value(MESSAGE_ID, bindMarker(MESSAGE_ID)));
    }

    private PreparedStatement prepareSelect(Session session) {
        return session.prepare(select(FIELDS)
            .from(TABLE_NAME)
            .where(eq(ID_AS_UUID, bindMarker(ID_AS_UUID))));
    }

    public Mono<DAOAttachment> getAttachment(AttachmentId attachmentId, Mono<CassandraMessageId> fallback) {
        Preconditions.checkArgument(attachmentId != null);
        return cassandraAsyncExecutor.executeSingleRow(
            selectStatement.bind()
                .setUUID(ID_AS_UUID, attachmentId.asUUID())
                .setConsistencyLevel(consistencyLevel))
            .flatMap(row -> CassandraAttachmentDAOV2.fromRow(row, blobIdFactory, fallback));
    }

    public Mono<Void> storeAttachment(DAOAttachment attachment) {
        CassandraMessageId messageId = (CassandraMessageId) attachment.getMessageId();
        return cassandraAsyncExecutor.executeVoid(
            insertStatement.bind()
                .setUUID(ID_AS_UUID, attachment.getAttachmentId().asUUID())
                .setString(ID, attachment.getAttachmentId().getId())
                .setLong(SIZE, attachment.getSize())
                .setUUID(MESSAGE_ID, messageId.get())
                .setString(TYPE, attachment.getType().asString())
                .setString(BLOB_ID, attachment.getBlobId().asString()));
    }

    public Mono<Void> insertMessageId(AttachmentId attachmentId, MessageId messageId) {
        CassandraMessageId cassandraMessageId = (CassandraMessageId) messageId;
        return cassandraAsyncExecutor.executeVoid(
            insertMessageIdStatement.bind()
                .setUUID(ID_AS_UUID, attachmentId.asUUID())
                .setUUID(MESSAGE_ID, cassandraMessageId.get()));
    }

    public Mono<Void> delete(AttachmentId attachmentId) {
        return cassandraAsyncExecutor.executeVoid(
            deleteStatement.bind()
                .setUUID(ID_AS_UUID, attachmentId.asUUID()));
    }

    public Flux<BlobId> listBlobs() {
        return cassandraAsyncExecutor.executeRows(listBlobs.bind())
            .map(row -> blobIdFactory.from(row.getString(BLOB_ID)));
    }
}
