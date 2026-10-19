plugins {
    id("no.nav.sykepenger.deployable")
    // Kreves av ktors Resources-plugin for å generere serializers for @Resource-klassene
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

sykepengerDeployable {
    mainClass = "no.nav.helse.sykepenger.medlemskap.bootstrap.AppKt"
}

dependencies {
    implementation(libs.rapids.and.rivers)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.jackson3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.smiley4.ktor.openapi.tools)
    implementation(libs.sykepenger.logging)
    implementation(libs.httpclient5.fluent)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.tbd.libs.naisful.postgres)
    implementation(libs.tbd.libs.access.token.provider.texas)
    implementation(libs.tbd.libs.person.pseudo.id)
    implementation(libs.tbd.libs.populasjonstilgangskontroll.provider.api)
    implementation(libs.tbd.libs.populasjonstilgangskontroll.provider.tilgangsmaskinen)

    implementation(libs.kotliquery)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":migreringer"))
    testImplementation(libs.rapids.and.rivers.test)
    testImplementation(libs.wiremock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mock.oauth2.server)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.postgres)
}
