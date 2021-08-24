package com.netflix.spinnaker.keel.sql

import de.huxhorn.sulky.ulid.ULID
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.ResultQuery
import org.jooq.impl.DSL

internal fun <R> DSLContext.inTransaction(fn: DSLContext.() -> R): R =
  transactionResult { tx ->
    DSL.using(tx).run(fn)
  }

internal inline fun <reified E> Record.into(): E = into(E::class.java)

internal inline fun <reified T> field(sql: String): Field<T> = DSL.field(sql, T::class.java)

internal inline fun <reified RESULT> ResultQuery<*>.fetchOneInto() =
  fetchOneInto(RESULT::class.java)

internal inline fun <reified RESULT> ResultQuery<*>.fetchSingleInto() =
  fetchSingleInto(RESULT::class.java)
