package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapsvurderingRepository
import no.nav.helse.sykepenger.medlemskap.domain.Kategori
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurderingskilde
import no.nav.helse.sykepenger.medlemskap.domain.Utfall
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.VilkårsvurderingId
import org.intellij.lang.annotations.Language
import org.postgresql.util.PSQLException
import java.time.LocalDate

private const val VURDERINGSKILDE_VURDERT_I_SP_medlemskap = "VURDERT_I_SP_medlemskap"
private const val VURDERINGSKILDE_OVERFORT_FRA_SPLEIS = "OVERFORT_FRA_SPLEIS"
private const val VURDERINGSKILDE_OVERFOERT_FRA_INFOTRYGD = "OVERFOERT_FRA_INFOTRYGD"

internal class PostgresMedlemskapsvurderingRepository(
    private val session: Session,
) : MedlemskapsvurderingRepository {
    override fun lagre(vurdering: Medlemskapsvurdering) {
        try {
            when (vurdering) {
                is Medlemskapsvurdering.VurdertISpVilkårsprøving -> lagreMedVilkårsvurderinger(vurdering, VURDERINGSKILDE_VURDERT_I_SP_medlemskap)
                is Medlemskapsvurdering.OverførtFraSpleis -> lagreMedVilkårsvurderinger(vurdering, VURDERINGSKILDE_OVERFORT_FRA_SPLEIS)
                is Medlemskapsvurdering.OverførtFraInfotrygd -> lagreInfotrygd(vurdering)
            }
        } catch (e: PSQLException) {
            if (e.sqlState != UNIKHETSBRUDD) throw e
            throw IllegalStateException("Medlemskapsvurdering ${vurdering.id} er allerede lagret. Vurderinger er immutable.", e)
        }
    }

    private fun lagreMedVilkårsvurderinger(
        totalvurdering: Medlemskapsvurdering.MedVilkårsvurderinger,
        vurderingskilde: String,
    ) {
        val vurdertTidspunkt = totalvurdering.vilkårsvurderinger.maxOfOrNull { it.vurdertTidspunkt }

        @Language("PostgreSQL")
        val medlemskapsvurderingSql = """
            insert into medlemskapsvurdering (
                id, fødselsnummer, skjæringstidspunkt, kategori, vurderingskilde, medlemskap_ok, vurdert_tidspunkt
            )
            values (
                :id, :fodselsnummer, :skjaeringstidspunkt, :kategori, :vurderingskilde, :medlemskap_ok, coalesce(:vurdertTidspunkt, now())
            )
        """
        session.run(
            queryOf(
                medlemskapsvurderingSql,
                mapOf(
                    "id" to totalvurdering.id.value,
                    "fodselsnummer" to totalvurdering.fødselsnummer,
                    "skjaeringstidspunkt" to totalvurdering.skjæringstidspunkt,
                    "kategori" to totalvurdering.kategori.tilDbVerdi(),
                    "vurderingskilde" to vurderingskilde,
                    "medlemskap_ok" to totalvurdering.erOk,
                    "vurdertTidspunkt" to vurdertTidspunkt,
                ),
            ).asUpdate,
        )

        // En videreført vilkårsvurdering gjenbruker samme id og finnes derfor allerede i tabellen —
        // ON CONFLICT DO NOTHING gjør innsettingen idempotent uten å måtte skille på om vurderingen er ny
        // eller videreført. Innholdet kan uansett ikke ha endret seg: vilkårsvurderinger er immutable.
        @Language("PostgreSQL")
        val vilkårsvurderingSql = """
            insert into vilkarsvurdering (id, vilkårskode, utfall, vurdert_tidspunkt, kilde, vurderingskilde, lovreferanse)
            values (:id, :vilkarskode, :utfall, :vurdertTidspunkt, cast(:kilde as jsonb), :vurderingskilde, cast(:lovreferanse as jsonb))
            on conflict (id) do nothing
        """

        @Language("PostgreSQL")
        val lagKoblingSql = """
            insert into medlemskapsvurdering_vilkarsvurdering (medlemskapsvurdering_id, vilkarsvurdering_id)
            values (:medlemskapsvurderingId, :vilkarsvurderingId)
        """
        totalvurdering.vilkårsvurderinger.forEach { enkeltvurdering ->
            session.run(
                queryOf(
                    vilkårsvurderingSql,
                    mapOf(
                        "id" to enkeltvurdering.id.value,
                        "vilkarskode" to enkeltvurdering.vilkårskode.name,
                        "utfall" to enkeltvurdering.utfall.name,
                        "vurdertTidspunkt" to enkeltvurdering.vurdertTidspunkt,
                        "kilde" to Vurderingskildejson.tilJson(enkeltvurdering.kilde),
                        "vurderingskilde" to enkeltvurdering.vurderingskilde.name,
                        "lovreferanse" to Lovreferansejson.tilJson(enkeltvurdering.lovreferanse),
                    ),
                ).asUpdate,
            )

            session.run(
                queryOf(
                    lagKoblingSql,
                    mapOf(
                        "medlemskapsvurderingId" to totalvurdering.id.value,
                        "vilkarsvurderingId" to enkeltvurdering.id.value,
                    ),
                ).asUpdate,
            )
        }
        totalvurdering.avgjørendeVilkårsvurdering?.let { avgjørende ->
            @Language("PostgreSQL")
            val sql = """
                update medlemskapsvurdering
                set avgjørende_vilkårsvurdering = :avgjorendeVilkarsvurderingId
                where id = :id
            """
            session.run(
                queryOf(
                    sql,
                    mapOf("id" to totalvurdering.id.value, "avgjorendeVilkarsvurderingId" to avgjørende.id.value),
                ).asUpdate,
            )
        }
    }

    private fun lagreInfotrygd(totalvurdering: Medlemskapsvurdering.OverførtFraInfotrygd) {
        @Language("PostgreSQL")
        val sql = """
            insert into medlemskapsvurdering (
                id, fødselsnummer, skjæringstidspunkt, kategori, vurderingskilde, medlemskap_ok, vurdert_tidspunkt
            )
            values (
                :id, :fodselsnummer, :skjaeringstidspunkt, :kategori, :vurderingskilde, :medlemskap_ok, :vurdertTidspunkt
            )
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "id" to totalvurdering.id.value,
                    "fodselsnummer" to totalvurdering.fødselsnummer,
                    "skjaeringstidspunkt" to totalvurdering.skjæringstidspunkt,
                    "kategori" to totalvurdering.kategori.tilDbVerdi(),
                    "vurderingskilde" to VURDERINGSKILDE_OVERFOERT_FRA_INFOTRYGD,
                    "medlemskap_ok" to totalvurdering.erOk,
                    "vurdertTidspunkt" to totalvurdering.vurdertTidspunkt,
                ),
            ).asUpdate,
        )
    }

    override fun gjeldende(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Medlemskapsvurdering? {
        @Language("PostgreSQL")
        val sql = """
            $SELECT_MEDLEMSKAPSVURDERING
            where fødselsnummer = :fodselsnummer and skjæringstidspunkt = :skjaeringstidspunkt
            order by vurdert_tidspunkt desc, opprettet desc
            limit 1
        """
        return session
            .run(
                queryOf(
                    sql,
                    mapOf(
                        "fodselsnummer" to fødselsnummer,
                        "skjaeringstidspunkt" to skjæringstidspunkt,
                    ),
                ).map(::tilMedlemskapsvurderingRad).asSingle,
            )?.let(::hydrer)
    }

    override fun historikk(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): List<Medlemskapsvurdering> {
        @Language("PostgreSQL")
        val sql = """
            $SELECT_MEDLEMSKAPSVURDERING
            where fødselsnummer = :fodselsnummer and skjæringstidspunkt = :skjaeringstidspunkt
            order by vurdert_tidspunkt desc, opprettet desc
        """
        return session
            .run(
                queryOf(
                    sql,
                    mapOf(
                        "fodselsnummer" to fødselsnummer,
                        "skjaeringstidspunkt" to skjæringstidspunkt,
                    ),
                ).map(::tilMedlemskapsvurderingRad).asList,
            ).map(::hydrer)
    }

    override fun finn(medlemskapsvurderingId: MedlemskapsvurderingId): Medlemskapsvurdering? {
        @Language("PostgreSQL")
        val sql = """
            $SELECT_MEDLEMSKAPSVURDERING
            where id = :id
        """
        return session
            .run(
                queryOf(sql, mapOf("id" to medlemskapsvurderingId.value)).map(::tilMedlemskapsvurderingRad).asSingle,
            )?.let(::hydrer)
    }

    private fun hydrer(rad: MedlemskapsvurderingRad): Medlemskapsvurdering =
        when (rad.vurderingskilde) {
            VURDERINGSKILDE_OVERFOERT_FRA_INFOTRYGD ->
                Medlemskapsvurdering.infotrygdFraLagring(
                    id = rad.id,
                    fødselsnummer = rad.fødselsnummer,
                    skjæringstidspunkt = rad.skjæringstidspunkt,
                    erOk = rad.erOk,
                    vurdertTidspunkt = rad.vurdertTidspunkt,
                )

            VURDERINGSKILDE_VURDERT_I_SP_medlemskap -> {
                val (vilkårsvurderinger, avgjørendeVilkårsvurdering) = finnVilkårsvurderingerOgAvgjørende(rad)
                Medlemskapsvurdering.fraLagring(
                    id = rad.id,
                    fødselsnummer = rad.fødselsnummer,
                    skjæringstidspunkt = rad.skjæringstidspunkt,
                    vilkårsvurderinger = vilkårsvurderinger,
                    avgjørendeVilkårsvurdering = avgjørendeVilkårsvurdering,
                    erOk = rad.erOk,
                    kategori = rad.kategori,
                )
            }

            VURDERINGSKILDE_OVERFORT_FRA_SPLEIS -> {
                val (vilkårsvurderinger, avgjørendeVilkårsvurdering) = finnVilkårsvurderingerOgAvgjørende(rad)
                Medlemskapsvurdering.spleisFraLagring(
                    id = rad.id,
                    fødselsnummer = rad.fødselsnummer,
                    skjæringstidspunkt = rad.skjæringstidspunkt,
                    vilkårsvurderinger = vilkårsvurderinger,
                    avgjørendeVilkårsvurdering = avgjørendeVilkårsvurdering,
                    erOk = rad.erOk,
                    kategori = rad.kategori,
                )
            }

            else -> error("Ukjent vurderingskilde for medlemskapsvurdering ${rad.id}: ${rad.vurderingskilde}")
        }

    private fun finnVilkårsvurderingerOgAvgjørende(rad: MedlemskapsvurderingRad): Pair<List<Vilkårsvurdering>, Vilkårsvurdering?> {
        val vilkårsvurderinger = finnVilkårsvurderingerFor(rad.id)
        val avgjørendeVilkårsvurdering =
            rad.avgjørendeVilkårsvurderingId?.let { id ->
                requireNotNull(vilkårsvurderinger.find { it.id == id }) {
                    "Avgjørende vilkårsvurdering $id er ikke knyttet til medlemskapsvurdering ${rad.id}"
                }
            }
        return vilkårsvurderinger to avgjørendeVilkårsvurdering
    }

    private fun finnVilkårsvurderingerFor(medlemskapsvurderingId: MedlemskapsvurderingId): List<Vilkårsvurdering> {
        @Language("PostgreSQL")
        val sql = """
            select v.id, v.vilkårskode, v.utfall, v.vurdert_tidspunkt, v.kilde, v.vurderingskilde, v.lovreferanse
            from medlemskapsvurdering_vilkarsvurdering ov
            join vilkarsvurdering v on v.id = ov.vilkarsvurdering_id
            where ov.medlemskapsvurdering_id = :medlemskapsvurderingId
        """
        return session.run(
            queryOf(sql, mapOf("medlemskapsvurderingId" to medlemskapsvurderingId.value)).map(::tilVilkårsvurdering).asList,
        )
    }

    private fun tilVilkårsvurdering(row: Row) =
        Vilkårsvurdering.fraLagring(
            id = VilkårsvurderingId(row.uuid("id")),
            vilkårskode = Vilkårskode.valueOf(row.string("vilkårskode")),
            utfall = Utfall.valueOf(row.string("utfall")),
            vurdertTidspunkt = row.instant("vurdert_tidspunkt"),
            kilde = Vurderingskildejson.fraJson(row.string("kilde")),
            vurderingskilde = Medlemskapsvurderingskilde.valueOf(row.string("vurderingskilde")),
            lovreferanse = Lovreferansejson.fraJson(row.string("lovreferanse")),
        )

    private fun tilMedlemskapsvurderingRad(row: Row) =
        MedlemskapsvurderingRad(
            id = MedlemskapsvurderingId(row.uuid("id")),
            fødselsnummer = row.string("fødselsnummer"),
            skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
            kategori = row.string("kategori").fraDbVerdi(),
            vurderingskilde = row.string("vurderingskilde"),
            erOk = row.boolean("medlemskap_ok"),
            avgjørendeVilkårsvurderingId = row.uuidOrNull("avgjørende_vilkårsvurdering")?.let(::VilkårsvurderingId),
            vurdertTidspunkt = row.instant("vurdert_tidspunkt"),
        )

    private data class MedlemskapsvurderingRad(
        val id: MedlemskapsvurderingId,
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val kategori: Kategori,
        val vurderingskilde: String,
        val erOk: Boolean,
        val avgjørendeVilkårsvurderingId: VilkårsvurderingId?,
        val vurdertTidspunkt: java.time.Instant,
    )

    private companion object {
        const val UNIKHETSBRUDD = "23505"

        @Language("PostgreSQL")
        const val SELECT_MEDLEMSKAPSVURDERING = """
            select id, fødselsnummer, skjæringstidspunkt, kategori, vurderingskilde, medlemskap_ok, avgjørende_vilkårsvurdering, vurdert_tidspunkt
            from medlemskapsvurdering
        """
    }

    private fun Kategori.tilDbVerdi(): String =
        when (this) {
            Kategori.Arbeidstaker -> "ARBEIDSTAKER"
            Kategori.SelvstendigNæringsdrivende -> "SELVSTENDIG_NÆRINGSDRIVENDE"
        }

    private fun String.fraDbVerdi(): Kategori =
        when (this) {
            "ARBEIDSTAKER" -> Kategori.Arbeidstaker
            "SELVSTENDIG_NÆRINGSDRIVENDE" -> Kategori.SelvstendigNæringsdrivende
            else -> error("Ukjent kategori i databasen: $this")
        }
}
