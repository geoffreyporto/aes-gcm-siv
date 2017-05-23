/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codahale.aesgcmsiv;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import okio.Buffer;
import okio.ByteString;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.gcm.GCMMultiplier;
import org.bouncycastle.crypto.modes.gcm.GCMUtil;
import org.bouncycastle.crypto.modes.gcm.Tables8kGCMMultiplier;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.Pack;

/**
 * An AES-GCM-SIV AEAD instance.
 *
 * @see <a href="https://tools.ietf.org/html/draft-irtf-cfrg-gcmsiv-04">draft-irtf-cfrg-gcmsiv-04</a>
 * @see <a href="https://eprint.iacr.org/2017/168">AES-GCM-SIV: Specification and Analysis</a>
 */
public class AEAD {

  private final ByteString key;

  /**
   * Creates a new {@link AEAD} instance with the given key.
   *
   * @param key the secret key; must be 16 or 32 bytes long
   */
  public AEAD(ByteString key) {
    if (key.size() != 16 && key.size() != 32) {
      throw new IllegalArgumentException("Key must be 16 or 32 bytes long");
    }
    this.key = key;
  }

  /**
   * Encrypts the given plaintext.
   *
   * @param nonce a 12-byte random nonce
   * @param plaintext a plaintext message (may be empty)
   * @param data authenticated data (may be empty)
   * @return the encrypted message
   */
  public ByteString seal(ByteString nonce, ByteString plaintext, ByteString data) {
    if (nonce.size() != 12) {
      throw new IllegalArgumentException("Nonce must be 12 bytes long");
    }

    final byte[] n = nonce.toByteArray();
    final byte[] p = plaintext.toByteArray();
    final byte[] d = data.toByteArray();
    final byte[] key = this.key.toByteArray();
    final byte[] authKey = subKey(key, 0, 1, n);
    final byte[] encKey = subKey(key, 2, key.length == 16 ? 3 : 5, n);
    final byte[] hash = polyval(authKey, p, d);
    for (int i = 0; i < n.length; i++) {
      hash[i] ^= n[i];
    }
    hash[hash.length - 1] &= ~0x80;
    final byte[] tag = aesECB(encKey, hash);
    final byte[] ctr = convertTag(tag);
    final byte[] ciphertext = aesCTR(encKey, ctr, p);
    return new Buffer().write(ciphertext).write(tag).readByteString();
  }

  /**
   * Decrypts the given encrypted message.
   *
   * @param nonce the 12-byte random nonce used to encrypt the message
   * @param ciphertext the returned value from {@link #seal(ByteString, ByteString, ByteString)}
   * @param data the authenticated data used to encrypt the message (may be empty)
   * @return the plaintext message
   */
  public Optional<ByteString> open(ByteString nonce, ByteString ciphertext, ByteString data) {
    if (nonce.size() != 12) {
      throw new IllegalArgumentException("Nonce must be 12 bytes long");
    }

    final byte[] n = nonce.toByteArray();
    final byte[] c = ciphertext.substring(0, ciphertext.size() - 16).toByteArray();
    final byte[] d = data.toByteArray();
    final byte[] key = this.key.toByteArray();
    final byte[] authKey = subKey(key, 0, 1, n);
    final byte[] encKey = subKey(key, 2, key.length == 16 ? 3 : 5, n);
    final byte[] tag = ciphertext.substring(c.length, ciphertext.size()).toByteArray();
    final byte[] ctr = convertTag(tag);
    final byte[] plaintext = aesCTR(encKey, ctr, c);

    final byte[] hash = polyval(authKey, plaintext, d);
    for (int i = 0; i < n.length; i++) {
      hash[i] ^= n[i];
    }
    hash[hash.length - 1] &= ~0x80;
    final byte[] actual = aesECB(encKey, hash);

    if (MessageDigest.isEqual(tag, actual)) {
      return Optional.of(ByteString.of(plaintext));
    }
    return Optional.empty();
  }

  private byte[] convertTag(byte[] tag) {
    final byte[] ctr = Arrays.copyOf(tag, tag.length);
    ctr[ctr.length - 1] |= 0x80;
    return ctr;
  }

  private byte[] polyval(byte[] h, byte[] plaintext, byte[] data) {
    final byte[] x = aeadBlock(plaintext, data);
    final GCMMultiplier multiplier = new Tables8kGCMMultiplier();
    multiplier.init(mulX_GHASH(h));

    final byte[] s = new byte[16];
    for (int i = 0; i < x.length; i += s.length) {
      final byte[] in = reverse(Arrays.copyOfRange(x, i, i + s.length));
      GCMUtil.xor(s, in);
      multiplier.multiplyH(s);
    }
    return reverse(s);
  }

  private byte[] mulX_GHASH(byte[] x) {
    final int[] ints = GCMUtil.asInts(reverse(x));
    GCMUtil.multiplyP(ints);
    return GCMUtil.asBytes(ints);
  }

  private byte[] reverse(byte[] x) {
    final byte[] out = new byte[x.length];
    for (int i = 0; i < x.length; i++) {
      out[x.length - i - 1] = x[i];
    }
    return out;
  }

  private byte[] aeadBlock(byte[] plaintext, byte[] data) {
    final int plaintextPad = (16 - (plaintext.length % 16)) % 16;
    final int dataPad = (16 - (data.length % 16)) % 16;
    final byte[] out = new byte[8 + 8 + plaintext.length + plaintextPad + data.length + dataPad];
    System.arraycopy(data, 0, out, 0, data.length);
    System.arraycopy(plaintext, 0, out, data.length + dataPad, plaintext.length);
    Pack.intToLittleEndian(data.length * 8, out, out.length - 16);
    Pack.intToLittleEndian(plaintext.length * 8, out, out.length - 8);
    return out;
  }

  private byte[] subKey(byte[] key, int ctrStart, int ctrEnd, byte[] nonce) {
    final byte[] in = new byte[16];
    System.arraycopy(nonce, 0, in, in.length - nonce.length, nonce.length);
    final byte[] out = new byte[(ctrEnd - ctrStart + 1) * 8];
    for (int ctr = ctrStart; ctr <= ctrEnd; ctr++) {
      Pack.intToLittleEndian(ctr, in, 0);
      final byte[] x = aesECB(key, in);
      System.arraycopy(x, 0, out, (ctr - ctrStart) * 8, 8);
    }
    return out;
  }

  private byte[] aesECB(byte[] key, byte[] input) {
    final AESEngine aes = new AESEngine();
    aes.init(true, new KeyParameter(key));
    final byte[] out = new byte[input.length];
    aes.processBlock(input, 0, out, 0);
    return out;
  }

  private byte[] aesCTR(byte[] key, byte[] counter, byte[] input) {
    final AESEngine aes = new AESEngine();
    aes.init(true, new KeyParameter(key));
    final byte[] out = new byte[input.length];
    long ctr = Pack.littleEndianToInt(counter, 0);
    final byte[] k = new byte[aes.getBlockSize()];
    for (int i = 0; i < input.length; i += 16) {
      aes.processBlock(counter, 0, k, 0);
      final int len = Math.min(16, input.length - i);
      GCMUtil.xor(k, input, i, len);
      System.arraycopy(k, 0, out, i, len);
      Pack.intToLittleEndian((int) ++ctr, counter, 0);
    }
    return out;
  }
}
