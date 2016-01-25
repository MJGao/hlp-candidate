/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.api;

import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.PrivateKey;
import org.hyperledger.common.PublicKey;
import org.junit.Test;

import java.math.BigInteger;
import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;


public class HomomorphicTest {
    private static SecureRandom random = new SecureRandom();

    @Test
    public void test() throws HyperLedgerException {

        byte[] offset = new byte[32];
        for (int j = 0; j < 10; ++j) {
            PrivateKey key = PrivateKey.createNew();
            for (int i = 0; i < 10; ++i) {
                random.nextBytes(offset);
                BigInteger o = new BigInteger(offset);
                PrivateKey ok = key.offsetKey(o);
                PublicKey pk = key.getPublic().offsetKey(o);
                assertEquals(ok.getPublic(), pk);
                BigInteger no = o.negate();
                assertEquals(key, ok.offsetKey(no));
            }
        }
    }
}
