/*
 * Copyright 2026 Ayyoub Amjahed Abed
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ayyoub.lineward.shared;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in a transaction that has the tenant declared.
 *
 * <p>Every policy in the schema compares {@code org_id} against
 * {@code app.current_org} (ADR-011), so a query issued without it does not
 * return the wrong rows: it fails, because {@code current_setting} is called
 * without a default on purpose. That is the whole reason this class can be a
 * convenience rather than a security control. The control is in the database,
 * where it cannot be skipped; forgetting to come through here is noisy, not
 * silent.
 *
 * <p>The setting is transaction scoped, which is what the third argument of
 * {@code set_config} means, and that matters more than it looks. Session scope
 * would stick to the pooled connection, and the next piece of work to borrow it
 * would inherit this tenant: the isolation mechanism would become the leak.
 *
 * <p>It lives in {@code shared} because every module that touches a business
 * table needs it. Putting it in the first module that happened to need one
 * would make the next module read another module's internals.
 */
public class TenantTransactions {

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    /**
     * Takes the transaction manager rather than a {@link TransactionTemplate},
     * because Spring Boot 4 no longer autoconfigures one: its
     * {@code TransactionAutoConfiguration} only declares the reactive
     * {@code TransactionalOperator}. Verified 2026-10-21 against
     * spring-boot-transaction 4.1.1 by reading the class, after a previous
     * version of this file assumed the Boot 3 behaviour.
     *
     * @see JdbcTransactionManager the implementation Boot autoconfigures for a
     *     plain {@code DataSource}
     */
    public TenantTransactions(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    /** Runs {@code work} and returns its value, with {@code orgId} as the tenant. */
    public <T> T inTenant(UUID orgId, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.sql("SELECT set_config('app.current_org', ?, true)")
                    .param(orgId.toString())
                    .query(String.class)
                    .single();
            return work.get();
        });
    }

    /** Same, for work with nothing to return. */
    public void runInTenant(UUID orgId, Runnable work) {
        inTenant(orgId, () -> {
            work.run();
            return null;
        });
    }
}
