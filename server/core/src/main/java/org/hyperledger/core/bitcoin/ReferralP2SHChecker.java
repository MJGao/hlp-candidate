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
package org.hyperledger.core.bitcoin;

import org.hyperledger.common.Outpoint;
import org.hyperledger.common.Transaction;
import org.hyperledger.common.TransactionInput;
import org.hyperledger.common.TransactionOutput;

import java.util.Map;


public class ReferralP2SHChecker implements P2SHChecker {
    @Override
    public boolean isPayToScriptHash(Map<Outpoint, Transaction> referred, TransactionInput input) {
        if (input.getSource().isNull())
            return false;
        TransactionOutput referral = referred.get(input.getSource()).getOutputs().get(input.getOutputIndex());
        return referral.getScript().isPayToScriptHash();
    }
}
