package com.mrdoob.browser

import com.mrdoob.browser.NavPolicy.Action
import com.mrdoob.browser.NavPolicy.Relation
import com.mrdoob.browser.NavPolicy.Rule
import com.mrdoob.browser.NavPolicy.SiteMode
import com.mrdoob.browser.NavPolicy.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPolicyTest {

    private val page = "https://www.example.com/page"

    private fun req(
        to: String, type: Type, user: Boolean, from: String = page, since: Long = Long.MAX_VALUE, hops: Int = 0
    ) = NavPolicy.Request(from, to, type, user, since, hops)

    private fun cfg(
        rules: Map<Rule, Boolean> = emptyMap(),
        allowed: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
        sites: Map<String, SiteMode> = emptyMap(),
        visited: Set<String> = emptySet()
    ) = NavPolicy.Config(rules, allowed, blocked, sites) { it in visited }

    private fun allOff() = Rule.entries.associateWith { false }

    // --- relations ---------------------------------------------------------------------

    @Test fun relations() {
        assertEquals(Relation.SAME_ORIGIN, NavPolicy.relation(page, "https://example.com/other"))
        assertEquals(Relation.SAME_SITE, NavPolicy.relation(page, "https://blog.example.com/"))
        assertEquals(Relation.SAME_SITE, NavPolicy.relation(page, "http://example.com/"))
        assertEquals(Relation.SAME_SITE, NavPolicy.relation(page, "https://example.com:8443/"))
        assertEquals(Relation.CROSS_SITE, NavPolicy.relation(page, "https://example.org/"))
        assertEquals(Relation.CROSS_SITE, NavPolicy.relation("https://a.co.uk/", "https://b.co.uk/"))
        assertEquals(Relation.SAME_SITE, NavPolicy.relation("https://a.shop.co.uk/", "https://www.shop.co.uk/"))
    }

    // --- 1. user-clicked links ---------------------------------------------------------------

    @Test fun clickedExternalLinkAsks() {
        val d = NavPolicy.decide(req("https://other.org/", Type.LINK_CLICK, true), cfg())
        assertEquals(Action.ASK, d.action)
        assertEquals(Relation.CROSS_SITE, d.relation)
    }

    @Test fun sameOriginLinkIsAllowed() {
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://example.com/next", Type.LINK_CLICK, true), cfg()).action)
    }

    @Test fun confirmEveryAsksEvenSameOrigin() {
        val c = cfg(mapOf(Rule.CONFIRM_EVERY to true))
        assertEquals(Action.ASK, NavPolicy.decide(req("https://example.com/next", Type.LINK_CLICK, true), c).action)
    }

    @Test fun sameOriginRuleOffFallsThroughToOtherRules() {
        val c = cfg(mapOf(Rule.ALLOW_SAME_ORIGIN to false))
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://example.com/next", Type.LINK_CLICK, true), c).action)
    }

    // --- 2/3. JavaScript location changes -----------------------------------------------------------

    @Test fun scriptNavigationCrossSiteAsks() {
        val d = NavPolicy.decide(req("https://other.org/", Type.SCRIPT, false), cfg(visited = setOf("other.org")))
        assertEquals(Action.ASK, d.action)
        assertTrue(d.reason.contains("JavaScript"))
    }

    @Test fun scriptRuleOffAndNothingElseAsking() {
        val c = cfg(allOff() + (Rule.SMART_WARNINGS to false), visited = setOf("other.org"))
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://other.org/", Type.SCRIPT, false), c).action)
    }

    @Test fun scriptSameOriginIsAllowed() {
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://example.com/x", Type.SCRIPT, false), cfg()).action)
    }

    // --- 4/5. automatic redirects, HTTP and meta refresh ---------------------------------------------

    @Test fun httpRedirectCrossSiteAsks() {
        val d = NavPolicy.decide(req("https://tracker.net/r", Type.HTTP_REDIRECT, false), cfg())
        assertEquals(Action.ASK, d.action)
    }

    @Test fun metaRefreshAsks() {
        val d = NavPolicy.decide(req("https://other.org/", Type.META_REFRESH, false), cfg())
        assertEquals(Action.ASK, d.action)
        assertTrue(d.reason.contains("redirect"))
    }

    @Test fun automaticTypeAsks() {
        assertEquals(Action.ASK, NavPolicy.decide(req("https://other.org/", Type.AUTOMATIC, false), cfg()).action)
    }

    @Test fun httpsUpgradeOfSameHostIsSilentByDefault() {
        val r = req("https://example.com/", Type.HTTP_REDIRECT, false, from = "http://example.com/")
        assertEquals(Action.ALLOW, NavPolicy.decide(r, cfg()).action)
    }

    @Test fun httpsUpgradeAsksWhenEnabled() {
        val r = req("https://example.com/", Type.HTTP_REDIRECT, false, from = "http://example.com/")
        assertEquals(Action.ASK, NavPolicy.decide(r, cfg(mapOf(Rule.CONFIRM_HTTPS_UPGRADE to true))).action)
    }

    @Test fun httpsUpgradeToAnotherHostIsNotAnUpgrade() {
        val r = req("https://other.org/", Type.HTTP_REDIRECT, false, from = "http://example.com/")
        assertEquals(Action.ASK, NavPolicy.decide(r, cfg()).action)
    }

    // --- 6. forms -------------------------------------------------------------------------------------

    @Test fun crossSiteFormAsks() {
        assertEquals(Action.ASK, NavPolicy.decide(req("https://pay.other.org/submit", Type.FORM, true), cfg()).action)
    }

    @Test fun sameOriginFormIsAllowed() {
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://example.com/login", Type.FORM, true), cfg()).action)
    }

    // --- 7/8. new windows ---------------------------------------------------------------------------------

    @Test fun newWindowAsks() {
        assertEquals(Action.ASK, NavPolicy.decide(req("https://other.org/", Type.NEW_WINDOW, true), cfg()).action)
    }

    @Test fun newWindowRuleAloneStillAsks() {
        val c = cfg(allOff() + (Rule.CONFIRM_NEW_WINDOW to true))
        val d = NavPolicy.decide(req("https://other.org/", Type.NEW_WINDOW, true), c)
        assertEquals(Action.ASK, d.action)
        assertTrue(d.reason.contains("new window"))
    }

    @Test fun popUnderWithoutGestureIsWarnedAbout() {
        val d = NavPolicy.decide(req("https://other.org/", Type.NEW_WINDOW, false), cfg())
        assertEquals(Action.ASK, d.action)
        assertTrue(d.warnings.any { it.contains("pop") })
    }

    // --- 9. external URL schemes -----------------------------------------------------------------------------

    @Test fun externalSchemeAsks() {
        val d = NavPolicy.decide(req("market://details?id=x", Type.EXTERNAL_SCHEME, true), cfg())
        assertEquals(Action.ASK, d.action)
    }

    @Test fun externalSchemeAllowListAndBlockList() {
        assertEquals(Action.ALLOW, NavPolicy.decide(req("tel:123", Type.EXTERNAL_SCHEME, true), cfg(allowed = setOf("tel:"))).action)
        assertEquals(Action.BLOCK, NavPolicy.decide(req("tel:123", Type.EXTERNAL_SCHEME, true), cfg(blocked = setOf("tel:"))).action)
        assertEquals(Action.ALLOW, NavPolicy.decide(req("tel:123", Type.EXTERNAL_SCHEME, true), cfg(mapOf(Rule.CONFIRM_SCHEME to false))).action)
    }

    // --- subdomains -----------------------------------------------------------------------------------------------

    @Test fun subdomainsAreNotTrustedByDefault() {
        assertEquals(Action.ASK, NavPolicy.decide(req("https://blog.example.com/", Type.LINK_CLICK, true), cfg()).action)
    }

    @Test fun subdomainsTrustedWhenConfigured() {
        val c = cfg(mapOf(Rule.TRUST_SUBDOMAINS to true))
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://blog.example.com/", Type.LINK_CLICK, true), c).action)
    }

    @Test fun wildcardEntriesCoverSubdomains() {
        val c = cfg(allowed = setOf("*.example.org"))
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://a.b.example.org/", Type.LINK_CLICK, true), c).action)
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://example.org/", Type.LINK_CLICK, true), c).action)
        assertEquals(Action.ASK, NavPolicy.decide(req("https://notexample.org/", Type.LINK_CLICK, true), c).action)
    }

    // --- lists and per-site rules -------------------------------------------------------------------------------------

    @Test fun blocklistWinsOverAllowlist() {
        val c = cfg(allowed = setOf("other.org"), blocked = setOf("other.org"))
        assertEquals(Action.BLOCK, NavPolicy.decide(req("https://other.org/", Type.LINK_CLICK, true), c).action)
    }

    @Test fun allowlistSkipsConfirmation() {
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://other.org/", Type.SCRIPT, false), cfg(allowed = setOf("other.org"))).action)
    }

    @Test fun siteModes() {
        val to = "https://other.org/"
        assertEquals(Action.ALLOW, NavPolicy.decide(req(to, Type.SCRIPT, false), cfg(sites = mapOf("example.com" to SiteMode.ALLOW))).action)
        assertEquals(Action.BLOCK, NavPolicy.decide(req(to, Type.LINK_CLICK, true), cfg(sites = mapOf("example.com" to SiteMode.BLOCK))).action)
        val ask = cfg(allOff() + (Rule.SMART_WARNINGS to false), sites = mapOf("example.com" to SiteMode.ASK))
        assertEquals(Action.ASK, NavPolicy.decide(req(to, Type.LINK_CLICK, true), ask).action)
    }

    @Test fun siteBlockDoesNotStopSameOriginNavigation() {
        val c = cfg(sites = mapOf("example.com" to SiteMode.BLOCK))
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://example.com/b", Type.LINK_CLICK, true), c).action)
    }

    @Test fun visitedDomainsAreNotUnknown() {
        val c = cfg(allOff() + (Rule.CONFIRM_UNKNOWN to true), visited = setOf("other.org"))
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://other.org/", Type.LINK_CLICK, true), c).action)
        assertEquals(Action.ASK, NavPolicy.decide(req("https://new.org/", Type.LINK_CLICK, true), c).action)
    }

    // --- 5. smart protection -------------------------------------------------------------------------------------------------

    @Test fun rapidAutomaticNavigationIsFlagged() {
        val w = NavPolicy.warnings(req("https://other.org/", Type.SCRIPT, false, since = 400), Relation.CROSS_SITE, "other.org")
        assertTrue(w.any { it.contains("right after") })
    }

    @Test fun repeatedRedirectsAreFlagged() {
        val w = NavPolicy.warnings(req("https://c.org/", Type.HTTP_REDIRECT, false, hops = 3), Relation.CROSS_SITE, "c.org")
        assertTrue(w.any { it.contains("Repeated redirects") })
    }

    @Test fun adLikeNamesAreFlaggedByNameOnly() {
        val w = NavPolicy.warnings(req("https://ads.example.net/", Type.AUTOMATIC, false), Relation.CROSS_SITE, "ads.example.net")
        assertTrue(w.any { it.contains("advertising") })
        val none = NavPolicy.warnings(req("https://shop.example.net/", Type.AUTOMATIC, false), Relation.CROSS_SITE, "shop.example.net")
        assertTrue(none.none { it.contains("advertising") })
    }

    @Test fun userInitiatedNavigationIsNeverFlagged() {
        assertTrue(NavPolicy.warnings(req("https://other.org/", Type.LINK_CLICK, true, since = 100), Relation.CROSS_SITE, "other.org").isEmpty())
    }

    @Test fun suspiciousPatternAsksEvenWhenRulesAreOff() {
        val c = cfg(allOff() + (Rule.SMART_WARNINGS to true))
        val d = NavPolicy.decide(req("https://other.org/", Type.SCRIPT, false, since = 300), c)
        assertEquals(Action.ASK, d.action)
        assertTrue(d.warnings.isNotEmpty())
    }

    @Test fun smartWarningsCanBeSwitchedOff() {
        val c = cfg(allOff())
        assertEquals(Action.ALLOW, NavPolicy.decide(req("https://other.org/", Type.SCRIPT, false, since = 300), c).action)
    }

    @Test fun nonWebAddressesPassThrough() {
        assertEquals(Action.ALLOW, NavPolicy.decide(req("about:blank", Type.SCRIPT, false), cfg()).action)
    }
}
