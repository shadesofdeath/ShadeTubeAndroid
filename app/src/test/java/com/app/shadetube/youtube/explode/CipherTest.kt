package com.app.shadetube.youtube.explode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of external/YoutubeExplode/tests/CipherTests.cpp (the JS-engine / real-player cases need node: skipped). */
class CipherTest {
    @Test
    fun cipherOperations() {
        assertEquals("fedcba", CipherOperation(CipherOperation.Kind.Reverse, 0).apply("abcdef"))
        assertEquals("cdef", CipherOperation(CipherOperation.Kind.Splice, 2).apply("abcdef"))
        assertEquals("dbcaef", CipherOperation(CipherOperation.Kind.Swap, 3).apply("abcdef"))
        assertEquals("cbadef", CipherOperation(CipherOperation.Kind.Swap, 8).apply("abcdef")) // 8 % 6
    }

    @Test
    fun jsScannerBracketsLiteralsStatements() {
        val code = """a={b:"}",c:'{',d:/[}]/g,e:`${'$'}{f({})}`};x=1;function y(){return 1}if(a)b();else/x/.test(c);z()"""
        assertEquals(code.indexOf("};"), Js.findMatchingBracket(code, 2))
        val statements = Js.splitStatements(code)
        assertEquals(5, statements.size)
        assertEquals("function y(){return 1}", statements[2])
        assertEquals("if(a)b();else/x/.test(c)", statements[3])
        assertTrue(Js.isAssignmentStatement("a.b[c]=function(){}"))
        assertTrue(Js.isAssignmentStatement("x+=1"))
        assertFalse(Js.isAssignmentStatement("f(a=1)"))
        assertFalse(Js.isAssignmentStatement("a=1,b()"))
        assertFalse(Js.isAssignmentStatement("a&&(b=1)"))
        assertFalse(Js.isAssignmentStatement("a==b"))
        assertEquals("aAB\n", Js.parseStringLiteral("\"a\\x41\\u0042\\n\"", 0))
    }

    @Test
    fun playerSourceSyntheticPlayer() {
        val player = PlayerSource("synthetic", SyntheticPlayer.SOURCE)
        assertEquals("20668", player.signatureTimestamp)
        assertEquals("Q", player.globalArrayName)
        assertEquals(4, player.globalArray.size)
        assertNotNull(player.cipherManifest)
        assertEquals("sts=20668 [Swap (3), Reverse, Splice (2), Swap (61)]", player.cipherManifest.toString())
        assertEquals(SyntheticPlayer.reference(SyntheticPlayer.SIGNATURE), player.cipherManifest!!.decipher(SyntheticPlayer.SIGNATURE))
        assertNotNull(player.signatureScript)
        assertNotNull(player.nScript)
        // The `typeof Kx===Q[3]` early-return guard must have been stripped.
        assertFalse(player.nScript!!.contains("typeof"))
        val solver = player.solverScript
        assertNotNull(solver)
        // Side-effect statements are dropped, assignments and control statements are kept.
        assertFalse(solver!!.contains("appendChild"))
        assertTrue(solver.contains("_yte_solvers.push(kG)"))
        assertTrue(solver.contains("g.ok=1"))
    }

    @Test
    fun playerSourceWithoutSolver() {
        val player = PlayerSource("synthetic", SyntheticPlayer.SOURCE, buildSolver = false)
        assertEquals(null, player.solverScript)
        assertNotNull(player.cipherManifest)
    }
}

/**
 * A small, hand-written stand-in for YouTube's base.js that reproduces the structural patterns the extractors rely on
 * (global lookup array, cipher helper object, split/join callsite, indexed n function reference, a URL helper setting
 * alr=yes, and side-effect statements). Copied from the C++ tests/fixtures/SyntheticPlayer.hpp; no YouTube code.
 */
object SyntheticPlayer {
    val SOURCE = """var _yt_player={};(function(g){var window=this;'use strict';var Q="split;join;reverse;undefined".split(";");
var Xy={Ab:function(a,b){a.splice(0,b)},Cd:function(a){a.reverse()},
Ef:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
var Zq=function(a){a=a.split("");Xy.Ef(a,3);Xy.Cd(a,45);Xy.Ab(a,2);Xy.Ef(a,61);return a.join("")};
g.cfg=function(){return{signatureTimestamp:20668,other:"x"}};
var Nq=function(a){var b=a.split(""),c=[1,2,3];if(typeof Kx===Q[3])return a;
for(var d=0;d<b.length;d++){b[d]=String.fromCharCode(b[d].charCodeAt(0)+c[d%3])}b.reverse();return b.join("")};
var Apa=[Nq],Kx={};
g.Uv=function(a){var b;a.C&&(b=a.get("n"))&&(b=Apa[0](b),a.set("n",b),Apa.length||Nq(""))};
var Pz=function(a){this.q={};this.base=a};Pz.prototype.set=function(k,v){this.q[k]=v};
Pz.prototype.get=function(k){return this.q[k]};Pz.prototype.clone=function(){return new Pz(this.base)};
Pz.prototype.Tr=function(){var n=this.get("n");n&&this.set("n",Apa[0](n))};
var kG=function(r,z,P){z=z===void 0?"":z;P=P===void 0?"":P;r=new Pz(r);r.set("alr","yes");P&&r.set(z,encodeURIComponent(Zq(decodeURIComponent(P))));return r};
document.createElement("div").appendChild(null);
if(/^https?:/.test(window.location.href))g.ok=1;else g.ok=0;
})(_yt_player);
"""

    const val SIGNATURE =
        "NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hlMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzz"

    /** Straightforward re-implementation of the synthetic player's Zq() for cross-checking. */
    fun reference(input: String): String {
        fun swap(a: CharArray, b: Int) {
            val t = a[0]
            a[0] = a[b % a.size]
            a[b % a.size] = t
        }
        var s = input.toCharArray()
        swap(s, 3)
        s.reverse()
        s = s.copyOfRange(2, s.size)
        swap(s, 61)
        return String(s)
    }
}
