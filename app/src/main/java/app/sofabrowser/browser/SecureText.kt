package app.sofabrowser.browser

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps what the phone sends to the TV unreadable on the home network. The page is plain HTTP, where a phone's browser
 * has no WebCrypto, so the phone's page scrambles the text itself with the one thing every browser has in script form
 * here, SHA-256: HMAC-SHA256 run as a stream cipher (counter mode) and as a tag that must match before anything is
 * read. The key is made fresh for each page, shown to the phone only in the QR code's "#" part, which a browser never
 * sends, so someone listening on the network sees neither the key nor the text. Every message may be used only once.
 */
class SecureText internal constructor(private val key: ByteArray) {

    constructor() : this(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private val seen = HashSet<String>()

    /** The key for the page's address after "#". */
    val keyHex: String get() = hex(key)

    /** The text in [message] ("nonce.cipher.tag", each in hex), or null if it was altered, is not ours or was sent before. */
    @Synchronized
    fun open(message: String): String? {
        val parts = message.trim().split('.')
        if (parts.size != 3) return null
        val nonce = unhex(parts[0]) ?: return null
        val cipher = unhex(parts[1]) ?: return null
        val tag = unhex(parts[2]) ?: return null
        if (nonce.size != NONCE || cipher.size > MAX) return null
        val macKey = hmac(key, "mac".toByteArray())
        if (!MessageDigest.isEqual(hmac(macKey, nonce + cipher), tag)) return null
        if (!seen.add(parts[0])) return null
        return String(stream(hmac(key, "enc".toByteArray()), nonce, cipher), Charsets.UTF_8)
    }

    companion object {
        private const val NONCE = 16
        private const val MAX = 4096

        /** What the phone does, for tests: the message for [text] under [keyHex] with the given [nonce]. */
        internal fun seal(keyHex: String, nonce: ByteArray, text: String): String {
            val key = unhex(keyHex)!!
            val cipher = stream(hmac(key, "enc".toByteArray()), nonce, text.toByteArray(Charsets.UTF_8))
            val tag = hmac(hmac(key, "mac".toByteArray()), nonce + cipher)
            return "${hex(nonce)}.${hex(cipher)}.${hex(tag)}"
        }

        private fun stream(encKey: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            var block = 0
            var i = 0
            while (i < data.size) {
                val counter = byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte())
                val pad = hmac(encKey, nonce + counter)
                var j = 0
                while (j < pad.size && i < data.size) { out[i] = (data[i].toInt() xor pad[j].toInt()).toByte(); i++; j++ }
                block++
            }
            return out
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        private fun unhex(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            return ByteArray(s.length / 2) { (Character.digit(s[it * 2], 16) shl 4 or Character.digit(s[it * 2 + 1], 16)).also { v -> if (v < 0) return null }.toByte() }
        }

        /** The phone page's script side of the same scheme: SHA-256, HMAC and [seal] in plain JavaScript. */
        const val JS = """
function sha256(m){var K=[],H=[1779033703,3144134277,1013904242,2773480762,1359893119,2600822924,528734635,1541459225],p=2,n=0;
while(n<64){var ok=1;for(var q=2;q*q<=p;q++)if(p%q==0){ok=0;break}if(ok){K[n++]=(Math.pow(p,1/3)%1)*4294967296|0}p++}
var l=m.length,w=[],i,j;var b=new Uint8Array(((l+9+63)>>6)<<6);b.set(m);b[l]=128;var bl=l*8;b[b.length-4]=bl>>>24;b[b.length-3]=bl>>>16;b[b.length-2]=bl>>>8;b[b.length-1]=bl;
function r(x,c){return x>>>c|x<<32-c}
for(i=0;i<b.length;i+=64){for(j=0;j<16;j++)w[j]=b[i+j*4]<<24|b[i+j*4+1]<<16|b[i+j*4+2]<<8|b[i+j*4+3];
for(j=16;j<64;j++){var s0=r(w[j-15],7)^r(w[j-15],18)^w[j-15]>>>3,s1=r(w[j-2],17)^r(w[j-2],19)^w[j-2]>>>10;w[j]=w[j-16]+s0+w[j-7]+s1|0}
var a=H.slice();for(j=0;j<64;j++){var t1=a[7]+(r(a[4],6)^r(a[4],11)^r(a[4],25))+(a[4]&a[5]^~a[4]&a[6])+K[j]+w[j]|0,t2=(r(a[0],2)^r(a[0],13)^r(a[0],22))+(a[0]&a[1]^a[0]&a[2]^a[1]&a[2])|0;
a=[t1+t2|0,a[0],a[1],a[2],a[3]+t1|0,a[4],a[5],a[6]]}for(j=0;j<8;j++)H[j]=H[j]+a[j]|0}
var o=new Uint8Array(32);for(i=0;i<8;i++){o[i*4]=H[i]>>>24;o[i*4+1]=H[i]>>>16;o[i*4+2]=H[i]>>>8;o[i*4+3]=H[i]}return o}
function cat(a,b){var o=new Uint8Array(a.length+b.length);o.set(a);o.set(b,a.length);return o}
function hmac(k,m){var i,a=new Uint8Array(64),b=new Uint8Array(64);if(k.length>64)k=sha256(k);a.set(k);b.set(k);for(i=0;i<64;i++){a[i]^=54;b[i]^=92}return sha256(cat(b,sha256(cat(a,m))))}
function hex(b){var s='';for(var i=0;i<b.length;i++)s+=('0'+b[i].toString(16)).slice(-2);return s}
function unhex(s){var o=new Uint8Array(s.length/2);for(var i=0;i<o.length;i++)o[i]=parseInt(s.substr(i*2,2),16);return o}
function seal(keyHex,text){var key=unhex(keyHex),enc=hmac(key,new TextEncoder().encode('enc')),mac=hmac(key,new TextEncoder().encode('mac')),
nonce=crypto.getRandomValues(new Uint8Array(16)),d=new TextEncoder().encode(text),c=new Uint8Array(d.length),i,j,blk=0;
for(i=0;i<d.length;blk++){var pad=hmac(enc,cat(nonce,new Uint8Array([blk>>>24&255,blk>>>16&255,blk>>>8&255,blk&255])));for(j=0;j<32&&i<d.length;j++,i++)c[i]=d[i]^pad[j]}
return hex(nonce)+'.'+hex(c)+'.'+hex(hmac(mac,cat(nonce,c)))}
"""
    }
}
