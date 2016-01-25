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

import org.hyperledger.common.*;
import org.hyperledger.core.BitcoinValidatorConfig;
import org.hyperledger.core.ScriptValidator;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class TransactionTest {
    private static final String TX_VALID = "tx_valid.json";
    private static final String TX_INVALID = "tx_invalid.json";

    private WireFormatter wireFormatter = WireFormatter.bitcoin;

    private Set<Integer> skipCheckIndex = new HashSet<>(Arrays.asList(14, 41, 42, 44, 41, 45, 47, 48, 50, 52, 53, 55, 58, 60, 62, 63, 64, 66, 68, 70, 72, 74));

    @Parameters
    public static Iterable<Object[]> data() {
//        return Arrays.asList(new Object[][]{/*{ValidatorConfig.ScriptEngine.BITCOIN_JAVA}, */{BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS}});
        return Arrays.asList(new Object[][]{{BitcoinValidatorConfig.ScriptEngine.BITCOIN_JAVA}
//                , {BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS}
        });
    }

    @Parameter(0)
    public BitcoinValidatorConfig.ScriptEngine scriptValidationMode;

    private JSONArray readObjectArray(String resource) throws IOException, JSONException {
        InputStream input = this.getClass().getResource("/" + resource).openStream();
        StringBuilder content = new StringBuilder();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = input.read(buffer)) > 0) {
            byte[] s = new byte[len];
            System.arraycopy(buffer, 0, s, 0, len);
            content.append(new String(s, "UTF-8"));
        }
        return new JSONArray(content.toString());
    }


    ScriptValidator.ScriptValidation createScriptValidator(Transaction tx, int inr, TransactionOutput source, EnumSet<ScriptVerifyFlag> flags, boolean addDefaultFlags) {
        if (addDefaultFlags) {
            flags.addAll(ScriptVerifyFlag.defaultSet());
        }
        return createScriptValidator(tx, inr, source, flags);
    }

    ScriptValidator.ScriptValidation createScriptValidator(Transaction tx, int inr, TransactionOutput source, EnumSet<ScriptVerifyFlag> flags) {
        if (scriptValidationMode == BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS) {
            return new NativeBitcoinScriptEvaluation(tx, inr, source, flags, SignatureOptions.COMMON);
        } else {
            return new JavaBitcoinScriptEvaluation(tx, inr, source, flags, SignatureOptions.COMMON);
        }
    }

    @Test
    public void bitcoindValidTxTest() throws IOException, JSONException, HyperLedgerException {
        JSONArray testData = readObjectArray(TX_VALID);
        for (int i = 0; i < testData.length(); ++i) {
            JSONArray test = testData.getJSONArray(i);
            if (test.length() != 1) {
                Map<TID, HashMap<Integer, Script>> inputMap = new HashMap<>();
                for (int j = 0; j < test.length() - 2; ++j) {
                    JSONArray inputs = test.getJSONArray(j);
                    for (int k = 0; k < inputs.length(); ++k) {
                        JSONArray input = inputs.getJSONArray(k);
                        TID prevHash = new TID(input.getString(0));
                        Integer ix = input.getInt(1);
                        Script script = Script.fromReadable(input.getString(2));
                        HashMap<Integer, Script> in = inputMap.get(prevHash);
                        if (in == null) {
                            inputMap.put(prevHash, in = new HashMap<>());
                        }
                        in.put(ix, script);
                    }
                }
                Transaction tx = wireFormatter.fromWireDump(test.getString(test.length() - 2));
                EnumSet<ScriptVerifyFlag> flags = ScriptVerifyFlag.fromString(test.getString(test.length() - 1));
                int inr = 0;
                for (TransactionInput in : tx.getInputs()) {
                    TID sourceHash = in.getSourceTransactionID();
                    int sourceIx = in.getOutputIndex();
                    Script sourceScript = inputMap.get(sourceHash).get(sourceIx);
                    TransactionOutput source = new TransactionOutput.Builder()
                            .script(sourceScript).build();
                    // some tests have non-canonical sig
                    // TODO: update them to canonical
                    assertTrue(createScriptValidator(tx, inr, source, flags, false).validate().isValid());
                    ++inr;
                }
            }
        }
    }

    @Test
    public void bitcoindInvalidTxTest() throws IOException, JSONException, HyperLedgerException {
        JSONArray testData = readObjectArray(TX_INVALID);
        for (int i = 0; i < testData.length(); ++i) {
            JSONArray test = testData.getJSONArray(i);
            if (test.length() != 1) {
                Map<TID, HashMap<Integer, Script>> inputMap = new HashMap<>();
                for (int j = 0; j < test.length() - 2; ++j) {
                    JSONArray inputs = test.getJSONArray(j);
                    for (int k = 0; k < inputs.length(); ++k) {
                        JSONArray input = inputs.getJSONArray(k);
                        TID prevHash = new TID(input.getString(0));
                        Integer ix = input.getInt(1);
                        Script script = Script.fromReadable(input.getString(2));
                        HashMap<Integer, Script> in = inputMap.get(prevHash);
                        if (in == null) {
                            inputMap.put(prevHash, in = new HashMap<>());
                        }
                        in.put(ix, script);
                    }
                }
                Transaction tx = wireFormatter.fromWireDump(test.getString(test.length() - 2));
                EnumSet<ScriptVerifyFlag> flags = ScriptVerifyFlag.fromString(test.getString(test.length() - 1));
                int inr = 0;
                if (irrelevantInvalidTests().contains(tx.getID())) {
                    continue;
                }
                if (tx.getOutputs() == null) {
                    continue; // this tested for before script eval
                }
                if (tx.getInputs() != null && !tx.getInputs().isEmpty()  && !skipCheckIndex.contains(i)) { // this is tested for before script evaluation
                    AssertAtleastOneInputScriptValidationIsFalse(inputMap, tx, flags, inr);
                }
            }
        }
    }

    private Set<TID> irrelevantInvalidTests() {
        return new HashSet<TID>(Arrays.asList(
                // this tests for negative output not relevant here.
                new TID("0ea8dd5d0a5d36350fc1ed1ade25df63c6dc98f966b7b0546335bd966bfe3399"),
                // this tests for duplicate input not relevant here.
                new TID("6a6b568d403db6c0ff04d018035d406280f283f023726718e63411abe4d31363"),
                // this tests for coinbase script length not relevant here.
                new TID("bf93c6fe89592b2508f2876c6200d5ffadbd741e18c57b5e9fe5eff3101137b3"),
                // this tests for coinbase script length not relevant here.
                new TID("d12fb29f9b00aaa9e89f5c34a27f43dd73f729aa796b36da0738b97f00587d0b"),
                // this tests for coinbase use is not relevant here.
                new TID("c14dd04aa024d7befe2ea903084636e971ca576245b91b5fe5f20141537f8cad")
        ));
    }

    private void AssertAtleastOneInputScriptValidationIsFalse(Map<TID, HashMap<Integer, Script>> inputMap, Transaction tx, EnumSet<ScriptVerifyFlag> flags, int inr) {
        for (TransactionInput in : tx.getInputs()) {
            TID sourceHash = in.getSourceTransactionID();
            int sourceIx = in.getOutputIndex();
            Script sourceScript = inputMap.get(sourceHash).get(sourceIx);
            TransactionOutput source = new TransactionOutput.Builder()
                    .script(sourceScript).build();
            try {
                if (!createScriptValidator(tx, inr, source, flags).validate().isValid()) {
                    return;
                }
            } catch (Exception e) {
                // exceptions are OK here
            }
        }
        fail("expected atleast one script verification that was false but every verification was true or threw an exception");
    }

    @Test
    public void transactionTest1() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        WireFormat.Reader reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("0100000001169e1e83e930853391bc6f35f605c6754cfead57cf8387639d3b4096c54f18f40100000048473044022027542a94d6646c51240f23a76d33088d3dd8815b25e9ea18cac67d1171a3212e02203baf203c6e7b80ebd3e588628466ea28be572fe1aaa3f30947da4763dd3b3d2b01ffffffff0200ca9a3b00000000434104b5abd412d4341b45056d3e376cd446eca43fa871b51961330deebd84423e740daa520690e1d9e074654c59ff87b408db903649623e86f1ca5412786f61ade2bfac005ed0b20000000043410411db93e1dcdb8a016b49840f8c53bc1eb68a382e97b1482ecad7b148a6909a5cb2e0eaddfb84ccf9744464f82e160bfa9b8b64f9d4c03f999b8643f656b412a3ac00000000"));
        Transaction t1 = wireFormatter.fromWire(reader);
        assertTrue(t1.getID().equals(new TID("a16f3ce4dd5deb92d98ef5cf8afeaf0775ebca408f708b2146c4fb42b41e14be")));
    }

    @Test
    public void transactionTest2() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        WireFormat.Reader reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("0100000001c997a5e56e104102fa209c6a852dd90660a20b2d9c352423edce25857fcd3704000000004847304402204e45e16932b8af514961a1d3a1a25fdf3f4f7732e9d624c6c61548ab5fb8cd410220181522ec8eca07de4860a4acdd12909d831cc56cbbac4622082221a8768d1d0901ffffffff0200ca9a3b00000000434104ae1a62fe09c5f51b13905f07f06b99a2f7159b2225f374cd378d71302fa28414e7aab37397f554a7df5f142c21c1b7303b8a0626f1baded5c72a704f7e6cd84cac00286bee0000000043410411db93e1dcdb8a016b49840f8c53bc1eb68a382e97b1482ecad7b148a6909a5cb2e0eaddfb84ccf9744464f82e160bfa9b8b64f9d4c03f999b8643f656b412a3ac00000000"));
        Transaction t2 = wireFormatter.fromWire(reader);

        reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("0100000001169e1e83e930853391bc6f35f605c6754cfead57cf8387639d3b4096c54f18f40100000048473044022027542a94d6646c51240f23a76d33088d3dd8815b25e9ea18cac67d1171a3212e02203baf203c6e7b80ebd3e588628466ea28be572fe1aaa3f30947da4763dd3b3d2b01ffffffff0200ca9a3b00000000434104b5abd412d4341b45056d3e376cd446eca43fa871b51961330deebd84423e740daa520690e1d9e074654c59ff87b408db903649623e86f1ca5412786f61ade2bfac005ed0b20000000043410411db93e1dcdb8a016b49840f8c53bc1eb68a382e97b1482ecad7b148a6909a5cb2e0eaddfb84ccf9744464f82e160bfa9b8b64f9d4c03f999b8643f656b412a3ac00000000"));
        Transaction t1 = wireFormatter.fromWire(reader);

        assertTrue(t2.getID().equals(new TID("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16")));
        assertTrue(createScriptValidator(t1, 0, t2.getOutput(1), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest3() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        WireFormat.Reader reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("0100000001944badc33f9a723eb1c85dde24374e6dee9259ef4cfa6a10b2fd05b6e55be400000000008c4930460221009f8aef83489d5c3524b68ddf77e8af8ceb5cba89790d31d2d2db0c80b9cbfd26022100bb2c13e15bb356a4accdd55288e8b2fd39e204a93d849ccf749eaef9d8162787014104f9804cfb86fb17441a6562b07c4ee8f012bdb2da5be022032e4b87100350ccc7c0f4d47078b06c9d22b0ec10bdce4c590e0d01aed618987a6caa8c94d74ee6dcffffffff0100f2052a010000001976a9146934efcef36903b5b45ebd1e5f862d1b63a99fa588ac00000000"));
        Transaction t1 = wireFormatter.fromWire(reader);
        assertTrue(t1.getID().equals(new TID("74c1a6dd6e88f73035143f8fc7420b5c395d28300a70bb35b943f7f2eddc656d")));

        reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("01000000016d65dcedf2f743b935bb700a30285d395c0b42c78f3f143530f7886edda6c174000000008c493046022100b687c4436277190953466b3e4406484e89a4a4b9dbefea68cf5979f74a8ef5b1022100d32539ffb88736f3f9445fa6dd484b443ebb31af1471ee65071c7414e3ec007b014104f9804cfb86fb17441a6562b07c4ee8f012bdb2da5be022032e4b87100350ccc7c0f4d47078b06c9d22b0ec10bdce4c590e0d01aed618987a6caa8c94d74ee6dcffffffff0240420f000000000043410403c344438944b1ec413f7530aaa6130dd13562249d07d53ba96d8ac4f59832d05c837e36efd9533a6adf1920465fed2a4553fb357844f2e41329603c320753f4acc0aff62901000000434104f9804cfb86fb17441a6562b07c4ee8f012bdb2da5be022032e4b87100350ccc7c0f4d47078b06c9d22b0ec10bdce4c590e0d01aed618987a6caa8c94d74ee6dcac00000000"));
        Transaction t2 = wireFormatter.fromWire(reader);
        assertTrue(t2.getID().equals(new TID("131f68261e28a80c3300b048c4c51f3ca4745653ba7ad6b20cc9188322818f25")));

        assertTrue(createScriptValidator(t2, 0, t1.getOutput(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());

    }

    @Test
    public void transactionTest4() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        WireFormat.Reader reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("01000000017fd8dfdb54b5212c4e3151a39f4ffe279fd7f238d516a2ca731529c095d97449010000008b483045022100b6a7fe5eea81894bbdd0df61043e42780543457fa5581ac1af023761a098e92202201d4752785be5f9d1b9f8d362b8cf3b05e298a78c4abff874b838bb500dcf2a120141042e3c4aeac1ffb1c86ce3621afb1ca92773e02badf0d4b1c836eb26bd27d0c2e59ffec3d6ab6b8bbeca81b0990ab5224ebdd73696c4255d1d0c6b3c518a1a053effffffff01404b4c00000000001976a914dc44b1164188067c3a32d4780f5996fa14a4f2d988ac00000000"));
        Transaction t1 = wireFormatter.fromWire(reader);
        assertTrue(t1.getID().equals(new TID("406b2b06bcd34d3c8733e6b79f7a394c8a431fbf4ff5ac705c93f4076bb77602")));
    }

    @Test
    public void transactionTest5() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        WireFormat.Reader reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("01000000010276b76b07f4935c70acf54fbf1f438a4c397a9fb7e633873c4dd3bc062b6b40000000008c493046022100d23459d03ed7e9511a47d13292d3430a04627de6235b6e51a40f9cd386f2abe3022100e7d25b080f0bb8d8d5f878bba7d54ad2fda650ea8d158a33ee3cbd11768191fd004104b0e2c879e4daf7b9ab68350228c159766676a14f5815084ba166432aab46198d4cca98fa3e9981d0a90b2effc514b76279476550ba3663fdcaff94c38420e9d5000000000100093d00000000001976a9149a7b0f3b80c6baaeedce0a0842553800f832ba1f88ac00000000"));
        Transaction t2 = wireFormatter.fromWire(reader); // this is the transaction with the wrong SIGHASH_ALL
        reader =
                new WireFormat.Reader(
                        ByteUtils
                                .fromHex("01000000017fd8dfdb54b5212c4e3151a39f4ffe279fd7f238d516a2ca731529c095d97449010000008b483045022100b6a7fe5eea81894bbdd0df61043e42780543457fa5581ac1af023761a098e92202201d4752785be5f9d1b9f8d362b8cf3b05e298a78c4abff874b838bb500dcf2a120141042e3c4aeac1ffb1c86ce3621afb1ca92773e02badf0d4b1c836eb26bd27d0c2e59ffec3d6ab6b8bbeca81b0990ab5224ebdd73696c4255d1d0c6b3c518a1a053effffffff01404b4c00000000001976a914dc44b1164188067c3a32d4780f5996fa14a4f2d988ac00000000"));
        Transaction t1 = wireFormatter.fromWire(reader);

        assertTrue(t2.getID().equals(new TID("c99c49da4c38af669dea436d3e73780dfdb6c1ecf9958baa52960e8baee30e73")));

        assertTrue(createScriptValidator(t2, 0, t1.getOutput(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest6() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // S value negative
        Transaction t1 =
                wireFormatter.fromWireDump("0100000001289eb02e8ddc1ee3486aadc1cd1335fba22a8e3e87e3f41b7c5bbe7fb4391d81010000008a47304402206b5c3b1c86748dcf328b9f3a65e10085afcf5d1af5b40970d8ce3a9355e06b5b0220cdbdc23e6d3618e47056fccc60c5f73d1a542186705197e5791e97f0e6582a32014104f25ec495fa21ad14d69f45bf277129488cfb1a339aba1fed3c5099bb6d8e9716491a14050fbc0b2fed2963dc1e56264b3adf52a81b953222a2180d48b54d1e18ffffffff0140420f00000000001976a914e6ba8cc407375ce1623ec17b2f1a59f2503afc6788ac00000000");
        Transaction t2 =
                wireFormatter.fromWireDump("01000000014213d2fe8f942dd7a72df14e656baab0e8b2b7f59571771ddf170b588379a2b6010000008b483045022037183e3e47b23634eeebe6fd155f0adbde756bf00a6843a1317b6548a03f3cfe0221009f96bec8759837f844478a35e102618918662869188f99d32dffe6ef7f81427e014104a7d3b0dda6d4d0a44b137a65105cdfed890b09ce2d283d5683029f46a00e531bff1deb3ad3862e0648dca953a4250b83610c4f20861555a2f5638bd3d7aff93dffffffff02ddfb1100000000001976a9142256ff6b9b9fea32bfa8e64aed10ee695ffe100988ac40420f00000000001976a914c62301ef02dfeec757fb8dedb8a45eda5fb5ee4d88ac00000000");

        assertFalse(createScriptValidator(t1, 0, t2.getOutput(1), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest7() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // R value negative
        Transaction t1 =
                wireFormatter.fromWireDump("01000000024448a3999e6b39584d6acffbc620376d26ec88303913a137d286be0ea7c5931c000000008a473044022090f7346fa0f6a4dc4b31300bf93be229001a1104532829644e07f45814bb734e0220579da5a14362f46bfd7c2be0d19c67caedc812147b9b8d574e34a3932cf21f7a014104e9469f3c23309dd1eb3557ba2536ae7b58743425739c00c4af436998a0974d20edcb3c5a4cb621f103915df1271fdb56e58bd8161fbe24a726906328f48f9700ffffffff4448a3999e6b39584d6acffbc620376d26ec88303913a137d286be0ea7c5931c010000008a4730440220f7e67e0ffdd05f9c551bcf45ba94db0edb85907526ceece4d28269192edd082c0220cb5655b709086096412ffdfc0e3b8b74405da325a4701cfe2eddee41a3395982014104e9469f3c23309dd1eb3557ba2536ae7b58743425739c00c4af436998a0974d20edcb3c5a4cb621f103915df1271fdb56e58bd8161fbe24a726906328f48f9700ffffffff02a0c44a00000000001976a914623dbe779a29c6bc2615cd7bf5a35453f495e22988ac900e4d00000000001976a9149e969049aefe972e41aaefac385296ce18f3075188ac00000000");
        Transaction t2 =
                wireFormatter.fromWireDump("010000000104cc410a858127cad099f4ea6e1942a9a9002c14acc6d1bbbc223c8ec97e482a010000008a47304402207547807093f864090cb68a5913499ce75554404e8f47699bea33a78f2d63dabd0220706d44bfdf2c6e10a11b8c0b800eef5fb06ecaae60e2653a742c4b4d58436182014104e9469f3c23309dd1eb3557ba2536ae7b58743425739c00c4af436998a0974d20edcb3c5a4cb621f103915df1271fdb56e58bd8161fbe24a726906328f48f9700ffffffff02a0860100000000001976a9149e969049aefe972e41aaefac385296ce18f3075188ac904c9600000000001976a9149e969049aefe972e41aaefac385296ce18f3075188ac00000000");

        assertFalse(createScriptValidator(t1, 0, t2.getOutput(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest8() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // Multisig 1-1
        Transaction t1 =
                wireFormatter.fromWireDump("01000000024de8b0c4c2582db95fa6b3567a989b664484c7ad6672c85a3da413773e63fdb8000000006b48304502205b282fbc9b064f3bc823a23edcc0048cbb174754e7aa742e3c9f483ebe02911c022100e4b0b3a117d36cab5a67404dddbf43db7bea3c1530e0fe128ebc15621bd69a3b0121035aa98d5f77cd9a2d88710e6fc66212aff820026f0dad8f32d1f7ce87457dde50ffffffff4de8b0c4c2582db95fa6b3567a989b664484c7ad6672c85a3da413773e63fdb8010000006f004730440220276d6dad3defa37b5f81add3992d510d2f44a317fd85e04f93a1e2daea64660202200f862a0da684249322ceb8ed842fb8c859c0cb94c81e1c5308b4868157a428ee01ab51210232abdc893e7f0631364d7fd01cb33d24da45329a00357b3a7886211ab414d55a51aeffffffff02e0fd1c00000000001976a914380cb3c594de4e7e9b8e18db182987bebb5a4f7088acc0c62d000000000017142a9bc5447d664c1d0141392a842d23dba45c4f13b17500000000");
        Transaction t2 =
                wireFormatter.fromWireDump("01000000017ea56cd68c74b4cd1a2f478f361b8a67c15a6629d73d95ef21d96ae213eb5b2d010000006a4730440220228e4deb3bc5b47fc526e2a7f5e9434a52616f8353b55dbc820ccb69d5fbded502206a2874f7f84b20015614694fe25c4d76f10e31571f03c240e3e4bbf1f9985be201210232abdc893e7f0631364d7fd01cb33d24da45329a00357b3a7886211ab414d55affffffff0230c11d00000000001976a914709dcb44da534c550dacf4296f75cba1ba3b317788acc0c62d000000000017142a9bc5447d664c1d0141392a842d23dba45c4f13b17500000000");

        assertTrue(createScriptValidator(t1, 1, t2.getOutput(1), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionMultisig31Test() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // Multisig 3-1
        Transaction t1 =
                wireFormatter.fromWireDump("010000000290c5e425bfba62bd5b294af0414d8fa3ed580c5ca6f351ccc23e360b14ff7f470100000091004730440220739d9ab2c3e7089e7bd311f267a65dc0ea00f49619cb61ec016a5038016ed71202201b88257809b623d471e429787c36e0a9bcd2a058fc0c75fd9c25f905657e3b9e01ab512103c86390eb5230237f31de1f02e70ce61e77f6dbfefa7d0e4ed4f6b3f78f85d8ec2103193f28067b502b34cac9eae39f74dba4815e1278bab31516efb29bd8de2c1bea52aeffffffffdd7f3ce640a2fb04dbe24630aa06e4299fbb1d3fe585fe4f80be4a96b5ff0a0d01000000b400483045022100a28d2ace2f1cb4b2a58d26a5f1a2cc15cdd4cf1c65cee8e4521971c7dc60021c0220476a5ad62bfa7c18f9174d9e5e29bc0062df543e2c336ae2c77507e462bbf95701ab512103c86390eb5230237f31de1f02e70ce61e77f6dbfefa7d0e4ed4f6b3f78f85d8ec2103193f28067b502b34cac9eae39f74dba4815e1278bab31516efb29bd8de2c1bea21032462c60ebc21f4d38b3c4ccb33be77b57ae72762be12887252db18fd6225befb53aeffffffff02e0fd1c00000000001976a9148501106ab5492387998252403d70857acfa1586488ac50c3000000000000171499050637f553f03cc0f82bbfe98dc99f10526311b17500000000");
        Transaction t2 =
                wireFormatter.fromWireDump("0100000001eae7c33c5a3ad25316a4a1a0220343693077d7a35c6d242ed731d9f26c9f8b45010000006b48304502205b910ff27919bb4b81847e17e19848a8148373b5d84856e8a0798395c1a4df6e022100a9300a11b37b52997726dab17851914151bd647ca053d60a013b8e0ad42d1c6e012102b2e1e38d1b15170212a852f68045979d790814a139ed57bffba3763f75e18808ffffffff02b0453c00000000001976a914c39c8d989dfdd7fde0ee80be36113c5abcefcb9c88ac40420f0000000000171464d63d835705618da2111ca3194f22d067187cf2b17500000000");

        assertTrue(createScriptValidator(t1, 0, t2.getOutput(1), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest9() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t3 =
                wireFormatter.fromWireDump("0100000001d488bf79a92feb869c984de9fc6be7cb5c5ac2e408d608e25460501c2aff2dac010000008a47304402200f185ac16694f3f3902fb058f1a3d96f2549db4311b038742fc315685c9e6a1f022018e6c2c8e0559d87988b48ba80d214d95ed3f06795e549d4568702cc2a9e2af301410463cd01a8f2b56fff4e9357ccedf014ca119d64c1dff8b576e2785f603b3fd1a04e7ab451929ef5e4e2449a7999a1365db7bc08fccc19cdad16c4ce26d6ba9bf4ffffffff03008aa411000000001a76a91469d28eb9a311256338d281025a7437096149472c88ac610065cd1d000000001976a9145f8b65a4064ef5c071c382d594b55d94bd31ec3a88ac00100000000000001976a9146300bf4c5c2a724c280b893807afb976ec78a92b88ac00000000");
        assertFalse(t3.getOutput(0).getScript().isStandard());
    }

    @Test
    public void transactionOp1NegateOrOpWithinTest() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // FROM TESTNET3
        Transaction t1 =
                wireFormatter.fromWireDump("010000000560e0b5061b08a60911c9b2702cc0eba80adbe42f3ec9885c76930837db5380c001000000054f01e40164ffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b959000000000351519bffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b959020000000452018293ffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b95903000000045b5a5193ffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b95904000000045b5a5193ffffffff06002d310100000000029f91002d3101000000000401908f87002d31010000000001a0002d3101000000000705feffffff808730d39700000000001976a9140467f85e06a2ef0a479333b47258f4196fb94b2c88ac002d3101000000000604ffffff7f9c00000000");

        Transaction t2 =
                wireFormatter.fromWireDump("01000000017ae001aef566f8273a7cd14dcbc1d2bcd7927de792c5042375033991ef5523c3000000006a47304402200bb34283458a6f141fbea8dd9c3f4db0abb8dea2282364821886e60f313be94502201198397af91be19622e8ec1e52e2e7cb5ce21640d490f1ca7fb22452e01e1fee012103e4d7f9492784fc6b3439607be821148e0d4f11ca35de74521d277500203492eaffffffff024073a574000000001976a91457722497e036129a643d767d3a9559b9dea58d0788ac809698000000000001a500000000");

        // OP_1NEGATE e4 64 | OP_WITHIN
        assertTrue(createScriptValidator(t1, 0, t2.getOutput(1), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionOp2OrOpEqualTest() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("010000000560e0b5061b08a60911c9b2702cc0eba80adbe42f3ec9885c76930837db5380c001000000054f01e40164ffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b959000000000351519bffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b959020000000452018293ffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b95903000000045b5a5193ffffffff0d2fe5749c96f15e37ceed29002c7f338df4f2781dd79f4d4eea7a08aa69b95904000000045b5a5193ffffffff06002d310100000000029f91002d3101000000000401908f87002d31010000000001a0002d3101000000000705feffffff808730d39700000000001976a9140467f85e06a2ef0a479333b47258f4196fb94b2c88ac002d3101000000000604ffffff7f9c00000000");

        Transaction t2 =
                wireFormatter.fromWireDump("0100000001187a61689aee9607a8e044b71ce702e12e8f77242ad06c221056a2e62af8de9f000000006a47304402207d51095aa9656fa0d2d1f80388120842b451bdb63eebe363d21a53c0463fa858022079c8a5c6aad5a2b841c9ff6dd015a8a8c45815214beb8b6b5194cebadf65a404012103c772f985417a0edf508eda460fdc897db776b6374b15ddb37a024f54a2c918a7ffffffff05809698000000000001614010fd34000000001976a9141c3283713d3dcc8983e1cd0b171cac3303b3820188ac002d31010000000002008780c3c90100000000019c005a620200000000029d5100000000");

        // OP_2 82 OP_ADD | 0 OP_EQUAL
        assertTrue(createScriptValidator(t1, 2, t2.getOutput(2), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionOpNopOrOp1Test() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("0100000039487c43212019ccf8d7aadd11e379660c725252d62392b0320358e05a7d4b800f01000000025454ffffffffaf04daa3fe30686ac50ec79c723bdb0e63dd32b01c7b8fb813be64a4383ddb8801000000025b5affffffffee72cdc17d814687d2833acfa9522b516ce0408df1f360a39328472e72ba516f010000000b0004ffffffff04ffffff7fffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e88000000000804ffffff7f8f7693ffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e8801000000045b5a5193ffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e8802000000025b5affffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e880300000003515193ffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e88040000000704ffffff7f00a4ffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e880500000003018b5bffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e880600000004000164a4ffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e8808000000034f00a3ffffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e8809000000025b5affffffff758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e880a000000070004ffffffffa3ffffffff7eed6a9b31bde9eb8b5be9c52a2dc7086e4d0b8ba35507f41eeab9b451ad3adc00000000025b5affffffff7eed6a9b31bde9eb8b5be9c52a2dc7086e4d0b8ba35507f41eeab9b451ad3adc0100000003019090ffffffff7eed6a9b31bde9eb8b5be9c52a2dc7086e4d0b8ba35507f41eeab9b451ad3adc02000000025b5affffffff7eed6a9b31bde9eb8b5be9c52a2dc7086e4d0b8ba35507f41eeab9b451ad3adc030000000804ffffff7f8f7693ffffffff7eed6a9b31bde9eb8b5be9c52a2dc7086e4d0b8ba35507f41eeab9b451ad3adc040000008b483045022100c7fe9b6f31139a1ee27a62f39c61fe7a14dda171c7f1701d2ca3782b914f93f202205e0476298598272da5b65e1fb6f1fedc463f3661c5570ec45d05211a2797e4400141041dfd517e87086f22312edb04c14bf9413532571c3b10ce18831ed354b4c31f09b63ee3395f490eb1f8db6b7ff7f4db0378dfcf907883f2f512d4b37600bd79f2ffffffff7eed6a9b31bde9eb8b5be9c52a2dc7086e4d0b8ba35507f41eeab9b451ad3adc050000000704ffffff7f00a4ffffffffe6df90b4d83bf4c8beb39c3ef91d848573a1309cde0938516971ebed52ca95600100000003016f92ffffffff5e1c8690f71ab118633b871abe0a3768dc22c3b0f89a53ccd3eec55b5606a398010000000351519bffffffffec86cfd1a95ca2926028910053cb5b4b849d59864f73a5e339393e8d807056720100000003018b5bffffffffe1871049b9bec6119dcb1f3eb5e3739e36790a7fe1447cd855ca1b4213b421db01000000026090ffffffff518db6dec81b0ecf3c5b11b687d3b9cf7bf8800f128908b814b3762386c95af001000000020000ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef01000000030051a3ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef02000000025192ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef0300000003018b5bffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef0400000003019090ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef050000000161ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef0600000005016f5a5193ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef0700000003515193ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef080000000300009affffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef0900000004000164a4ffffffff94173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef0a000000070004ffffffffa3ffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb000000000804ffffff7f8f7693ffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb0200000003016f8cffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb030000000b0004ffffffff04ffffff7fffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb04000000020000ffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb05000000045b5a5193ffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb06000000025a5bffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb07000000025454ffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb08000000070004ffffffffa3ffffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb09000000025b5affffffff3163c76b0783271044b4c5f5d20f54d564de1ebf64dc04cdb4f315af8bd00dcb0a0000000704ffffff7f00a4ffffffffcd1080fa2175680e48642e2e60a76ce71dd3d6e02fa563411d42274bebfa2100010000000b0004ffffffff04ffffff7fffffffff27d71587cd6ad54965031719db154b2471c243a5309066f9048f8439dd0b6d7d01000000020000ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec4400000000025b5affffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec440100000003510051ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec4403000000025192ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec44040000000704ffffff7f7693ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec440500000006016f51935c94ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec440600000003019090ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec440700000003515193ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec4408000000025b5affffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec4409000000045b5a5193ffffffff48f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec440a000000020092ffffffff337d70b2faaad9e51b0f868c9d7b11fc7f61ae374e99cae73e652439ced657fc0100000004000164a4ffffffff0140700c1b000000001976a914f7cce8fe923ca6bf117d1d806948a8c50acd2a4288ac00000000");

        Transaction t2 =
                wireFormatter.fromWireDump("010000000114da74d7e2da39b2b2d676b957e3edc3619f4999922f3fa66c95b64be8fd92a3000000004a49304602210098dc303772a6969789d18d5a71bacc34b53b986c08ea54cb38991d9ec52123e8022100ee0d85fc13a6432512842ec917f17f111b5395ca18c7a0fdd3cca39fb5b8430001ffffffff0b0074af26010000001976a91474f0a927d6aa7ff3a103e59451c9bb7539ae3f1988ac200b20000000000002009ce0d14d000000000002518760566c000000000001a1201d9a00000000000401908f87a08f3e00000000000151a0987b0000000000019e604d2f000000000002528720145d00000000000191e0c81000000000000301649ce0da8a00000000000604ffffffff9c00000000");

        // OP_NOP | OP_1
        assertTrue(createScriptValidator(t1, 28, t2.getOutput(5), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionPush2Test() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000014f0ccb5158e5497900b7563c5e0ab7fad5e169b9f46e8ca24c84b1f2dc91911f000000008b483045022100fa76a695c678f040264492dc71a29310d58c78e208bd1d8e384ef507b7b7229902201fb047807170aa590dbc5ff4d6e5b4b050ccb0fc4385ced9e0c129338c6729490141044a3549784ccf7b3711e1a0482ba7ebcb252c2997c6422c5dfce88ccb797643fa485ba80a26ef977ea703b39f299421f032c74cac9847cafd3478466aa5fea16cffffffff03b0f21514010000001976a914a009272d8f4457aa878fbb378ca0a31f1bac69c888ac00e1f50500000000fd0b024d08026262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626200e1f50500000000ac515253545556576f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f00000000");

        // PUSH2 test
        t1 =
                wireFormatter.fromWireDump("010000003422300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf19560300000006011601150114ffffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf19560400000006011601150114ffffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf19560900000006050000008000ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f22909000000020051ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f2290a000000034f00a3ffffffff4f0ccb5158e5497900b7563c5e0ab7fad5e169b9f46e8ca24c84b1f2dc91911f030000000151ffffffff4f0ccb5158e5497900b7563c5e0ab7fad5e169b9f46e8ca24c84b1f2dc91911f040000000451525355ffffffff4f0ccb5158e5497900b7563c5e0ab7fad5e169b9f46e8ca24c84b1f2dc91911f0500000003016f8cffffffff4f0ccb5158e5497900b7563c5e0ab7fad5e169b9f46e8ca24c84b1f2dc91911f09000000025d5effffffff5649f4d40acc1720997749ede3abb24105e637dd309fb3deee4a49c49d3b4f1a0400000005016f5a5193ffffffff5649f4d40acc1720997749ede3abb24105e637dd309fb3deee4a49c49d3b4f1a06000000065a005b6b756cffffffff5649f4d40acc1720997749ede3abb24105e637dd309fb3deee4a49c49d3b4f1a080000000100ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c0100000006011601150114ffffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d6537040000000704ffffff7f7693ffffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d6537050000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797affffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d653708000000044d010008ffffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d65370a000000025191ffffffff6f3c0204703766775324115c32fd121a16f0df64f0336490157ebd94b62e059e02000000020075ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e0295020100000006016f51935c94ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e029502020000000403008000ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e029502060000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797affffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e02950207000000044f005152ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e0295020a00000003515193ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a330300000000025100ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a33030500000006011601150114ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a3303080000000100ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a33030a00000002010bffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2203000000014fffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe010000000100ffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe0300000006011601150114ffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe050000000351009affffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe060000000403ffff7fffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe090000000351009bffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad0200000006011601150114ffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad04000000045b5a5193ffffffffc162c5adb8f1675ad3a17b417076efc8495541bcb1cd0f11755f062fb49d1a7a010000000151ffffffffc162c5adb8f1675ad3a17b417076efc8495541bcb1cd0f11755f062fb49d1a7a08000000025d5effffffffc162c5adb8f1675ad3a17b417076efc8495541bcb1cd0f11755f062fb49d1a7a0a000000045b5a5193ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b06000000020051ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b0800000003028000ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d9620100000006011601150114ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d962030000000704ffffff7f7693ffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb5829010000000100ffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb582907000000025d5effffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb582909000000025b5affffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c030000000161ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c06000000034f4f93ffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a0200000006011601150114ffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a0300000003016f92ffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a050000000704ffffff7f7693ffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a08000000025173fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34060000000151ffffffff0290051000000000001976a914954659bcb93fdad012a00d825a9bce69dc7c6a2688ac800c49110000000008517a01158874528700000000");

        Transaction t2 =
                wireFormatter.fromWireDump("010000000194173b45a42a8eea30be107c5131d0d527913706850f100b4df44e20ec6d98ef000000008a47304402202caf4e621a477d119d9979e546d6fc039aeea0df314a9cfde4364de9cba507a002207749aba0f7f8dab09eb4dfde256623e2b412e888b561fced70d94568ecdd3023014104eb719bacfed45e78310f7ebe092a7ff5dc38662bd37887ee880910e15f3169e42dc88a921827044894f8fe0b4605c1c98a5fa78dd9da602493846e6c9f563e3dffffffff0b200b2000000000001ba9014c011414c286a1af0947f58d1ad787385b1c2c4a976f9e7187a08f3e0000000000056301506851e0d14d0000000000076463516700686860566c0000000000024f9c201d9a0000000000019ea0987b00000000000cb0b1b2b3b4b5b6b7b8b95187e0c81000000000000493011587604d2f0000000000085179011588745387e0da8a000000000005636267516800f65823010000001976a914c54cc5e7985aef373ecc88dc60dfb01f4bceaee388ac20145d00000000000382528700000000");

        // 0 | OP_IF OP_VER OP_ELSE OP_1 OP_ENDIF
        assertTrue(createScriptValidator(t1, 11, t2.getOutput(8), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionTest10() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000291e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e00000000020075ffffffff1e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e0a000000045b5a5193ffffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf1956010000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797affffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf195602000000025192ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f229060000000100ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f22907000000020000ffffffff5649f4d40acc1720997749ede3abb24105e637dd309fb3deee4a49c49d3b4f1a03000000034f00a3ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c00000000045b5a5193ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c03000000025100ffffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d653707000000034f4f93ffffffff6f3c0204703766775324115c32fd121a16f0df64f0336490157ebd94b62e059e09000000025a5bffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e02950208000000070004ffffffffa3ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e029502090000000151ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a3303070000000302ff7fffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0200000003510051ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0600000003019090ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a07000000034f4f93ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a09000000054f02e80393ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220200000006011601150114ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2207000000025b5affffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2208000000025d5effffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220a0000000151ffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe08000000020051ffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad03000000014fffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad080000000100ffffffffc162c5adb8f1675ad3a17b417076efc8495541bcb1cd0f11755f062fb49d1a7a090000000151ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b02000000025100ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b0500000003515193ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b070000000151ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d9620200000006011601150114ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d96206000000045b5a5193ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d962090000000904ffffffff01e40164ffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290400000003018b5bffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290a0000000b04ffffff7f04ffffffff93ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c0100000006011601150114ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c04000000025a5bffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a0a000000034f00a3fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34000000000804ffffff7f8f7693fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34040000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34090000000300009afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b340a000000045b5a5193ffffffff0240420f00000000001976a91466c9512832e733934858f4efaa4b708eda3efc1d88ac00a3e11100000000016900000000");

        Transaction t2 =
                wireFormatter.fromWireDump("0100000001b1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe070000008b48304502204c22de00f0cb984cea5a12f0a141fbe94848aaf1caac795c27e261e31c6bd8dd022100be78e7a068395f645da0fdc81a37f22ae047983bbad5b435a1c461f40efef9460141047e6028700e75da5d9531a84e50818d9b0a9e1dd69ebd963d2e9beb45ac960f6201893ebecfe1017eedaddbbda522e2ded8dbd38f0fc4b3fba9f1549e9f392d8cffffffff0ba06a5216000000001976a9145b454dc6daa0aedc28e7625532f7a2946f4a5df688ac201d9a000000000003016487e0c810000000000003825387a08f3e00000000000676aa7ca8a887604d2f0000000000085279011688745387e0d14d0000000000544c51417a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a27204551554187e0da8a00000000000482011a8720145d00000000000b6f745788939353886d0088a0987b00000000000604ffffffff9c60566c000000000003635168200b20000000000002528700000000");

        // 0 ffffffff OP_MIN | ffffffff OP_NUMEQUAL
        assertTrue(createScriptValidator(t1, 11, t2.getOutput(8), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionTest11() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000291e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e00000000020075ffffffff1e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e0a000000045b5a5193ffffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf1956010000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797affffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf195602000000025192ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f229060000000100ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f22907000000020000ffffffff5649f4d40acc1720997749ede3abb24105e637dd309fb3deee4a49c49d3b4f1a03000000034f00a3ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c00000000045b5a5193ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c03000000025100ffffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d653707000000034f4f93ffffffff6f3c0204703766775324115c32fd121a16f0df64f0336490157ebd94b62e059e09000000025a5bffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e02950208000000070004ffffffffa3ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e029502090000000151ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a3303070000000302ff7fffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0200000003510051ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0600000003019090ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a07000000034f4f93ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a09000000054f02e80393ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220200000006011601150114ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2207000000025b5affffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2208000000025d5effffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220a0000000151ffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe08000000020051ffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad03000000014fffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad080000000100ffffffffc162c5adb8f1675ad3a17b417076efc8495541bcb1cd0f11755f062fb49d1a7a090000000151ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b02000000025100ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b0500000003515193ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b070000000151ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d9620200000006011601150114ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d96206000000045b5a5193ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d962090000000904ffffffff01e40164ffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290400000003018b5bffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290a0000000b04ffffff7f04ffffffff93ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c0100000006011601150114ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c04000000025a5bffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a0a000000034f00a3fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34000000000804ffffff7f8f7693fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34040000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34090000000300009afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b340a000000045b5a5193ffffffff0240420f00000000001976a91466c9512832e733934858f4efaa4b708eda3efc1d88ac00a3e11100000000016900000000");

        Transaction t2 =
                wireFormatter.fromWireDump("0100000001b8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad090000008b48304502203ffd9d1f90f350336e754f2d9509b964704ca29053ca0a0287d9d7f8d20ab9b7022100b3951db4b6c8278cabfcaa155995e9bc355c014069c4b1fa8cd772227fb6ab640141049a4f1b39d73b14e0297b653d366b66373722e2ac31895eca7242e7bd3a35fd9630ca15de8e7ccc8ef10ea30c34600b9f87eb9cdc7eec8068070b646bbcbb07c8ffffffff0b604d2f0000000000025787e0d14d00000000001ba9014c011414c286a1af0947f58d1ad787385b1c2c4a976f9e7187a0987b000000000002a591a08f3e0000000000057c51880087200b20000000000003825187e0c81000000000000301648760566c00000000000401908f8720145d000000000003018287a0ed5d14000000001976a9144ebe6bb41b22f30dd2f6c68e888feb54a80bae0988ace0da8a00000000000402e70387201d9a000000000019a6011414f71c27109c692c1b56bbdceb5b9d2865b3708dbc8700000000");

        // System.out.println (new Script (t1, 17).toReadableConnected ());
        // OP_1NEGATE e803 OP_ADD | e703 OP_EQUAL
        assertTrue(createScriptValidator(t1, 17, t2.getOutput(9), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionOpNegateOpDupOpAddOrOpEqualTest() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000291e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e00000000020075ffffffff1e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e0a000000045b5a5193ffffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf1956010000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797affffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf195602000000025192ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f229060000000100ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f22907000000020000ffffffff5649f4d40acc1720997749ede3abb24105e637dd309fb3deee4a49c49d3b4f1a03000000034f00a3ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c00000000045b5a5193ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c03000000025100ffffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d653707000000034f4f93ffffffff6f3c0204703766775324115c32fd121a16f0df64f0336490157ebd94b62e059e09000000025a5bffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e02950208000000070004ffffffffa3ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e029502090000000151ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a3303070000000302ff7fffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0200000003510051ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0600000003019090ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a07000000034f4f93ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a09000000054f02e80393ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220200000006011601150114ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2207000000025b5affffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2208000000025d5effffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220a0000000151ffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe08000000020051ffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad03000000014fffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad080000000100ffffffffc162c5adb8f1675ad3a17b417076efc8495541bcb1cd0f11755f062fb49d1a7a090000000151ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b02000000025100ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b0500000003515193ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b070000000151ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d9620200000006011601150114ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d96206000000045b5a5193ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d962090000000904ffffffff01e40164ffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290400000003018b5bffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290a0000000b04ffffff7f04ffffffff93ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c0100000006011601150114ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c04000000025a5bffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a0a000000034f00a3fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34000000000804ffffff7f8f7693fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34040000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34090000000300009afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b340a000000045b5a5193ffffffff0240420f00000000001976a91466c9512832e733934858f4efaa4b708eda3efc1d88ac00a3e11100000000016900000000");

        Transaction t2 =
                wireFormatter.fromWireDump("0100000001758c2505677a5c4a4dd26767f0ac7e03dd3d3c04a81d298c1799aa9fb55b1e88070000008b4830450221008e4ece3337b0ecca5ef6b35d398d0e9bfc12f8072dc1699b535156ede9fd13db0220113ed599752feb7d3316411f2e74763e6437d95b7821d937cf61314e73622a2901410459c3aea943522322d4771ff9ae61bbea1c33de19aaadb0a6e5dc0f650db937bd3a93bffba6497c9b3eceff8f3250dd1891d98c6f64079f70c2fa91339c05d017ffffffff0b200b2000000000000705feffffff8087604d2f000000000013114e4f505f315f746f5f313027204551554187a08f3e0000000000057c51880087e0da8a000000000025a8012020e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85587a0987b00000000000482011a87e0c8100000000000029f9160566c00000000000cb0b1b2b3b4b5b6b7b8b9518700f65823010000001976a914a90494bf5ddccc364aa66d117f915b6414ab9a0388ace0d14d0000000000066301fd675168201d9a0000000000019120145d0000000000029e9100000000");

        // ffffff7f OP_NEGATE OP_DUP OP_ADD | feffffff80 OP_EQUAL
        assertTrue(createScriptValidator(t1, 37, t2.getOutput(0), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionTest12() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000291e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e00000000020075ffffffff1e82d7694a1bb1a4346993dafb875fbf67fcb0280708f07da1ae30f6494cf30e0a000000045b5a5193ffffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf1956010000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797affffffff22300a976c1f0f6bd6172ded8cb76c23f6e57d3b19e9ff1f403990e70acf195602000000025192ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f229060000000100ffffffff42e9e966f8c293ad44c0b726ec85c5338d1f30cee63aedfb6ead49571477f22907000000020000ffffffff5649f4d40acc1720997749ede3abb24105e637dd309fb3deee4a49c49d3b4f1a03000000034f00a3ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c00000000045b5a5193ffffffff67e36cd8a0a57458261704363fc21ce927b8214b381bcf86c0b6bd8f23e5e70c03000000025100ffffffff6ded57e5e632ec542b8ab851df40400c32052ce2b999cf2c6c1352872c5d653707000000034f4f93ffffffff6f3c0204703766775324115c32fd121a16f0df64f0336490157ebd94b62e059e09000000025a5bffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e02950208000000070004ffffffffa3ffffffff8f339185bdf4c571055114df3cbbb9ebfa31b605b99c4088a1b226f88e029502090000000151ffffffff925f27a4db9032976b0ed323094dcfd12d521f36f5b64f4879a20750729a3303070000000302ff7fffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0200000003510051ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a0600000003019090ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a07000000034f4f93ffffffffa7845145b7902ff1a240570e3121ca63007cef574315256ec8bdc4d0c78f769a09000000054f02e80393ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220200000006011601150114ffffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2207000000025b5affffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd2208000000025d5effffffffadb5b4d9c20de237a2bfa5543d8d53546fdeffed9b114e307b4d6823ef5fcd220a0000000151ffffffffb1ecb9e79ce8f54e8529feeeb668a72a7f0c49831f83d76cfbc83155b8b9e1fe08000000020051ffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad03000000014fffffffffb8870d0eb7a246fe332401c2f44c59417d56b30de2640514add2e54132cf4bad080000000100ffffffffc162c5adb8f1675ad3a17b417076efc8495541bcb1cd0f11755f062fb49d1a7a090000000151ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b02000000025100ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b0500000003515193ffffffffcc68b898c71166468049c9a4130809555908c30f3c88c07e6d28d2f6a6bb486b070000000151ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d9620200000006011601150114ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d96206000000045b5a5193ffffffffce1cba7787ec167235879ca17f46bd4bfa405f9e3e2e35c544537bbd65a5d962090000000904ffffffff01e40164ffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290400000003018b5bffffffffd6bb18a96b21035e2d04fcd54f2f503d199aeb86b8033535e06ffdb400fb58290a0000000b04ffffff7f04ffffffff93ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c0100000006011601150114ffffffffd878941d1968d5027129e4b462aead4680bcce392c099d50f294063a528dad9c04000000025a5bffffffffe7ea17c77cbad48a8caa6ca87749ef887858eb3becc55c65f16733837ad5043a0a000000034f00a3fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34000000000804ffffff7f8f7693fffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34040000001b1a6162636465666768696a6b6c6d6e6f707172737475767778797afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b34090000000300009afffffffff24629f6d9f2b7753e1b6fe1104f8554de1ce6be0dfb4f262a28c38587ed5b340a000000045b5a5193ffffffff0240420f00000000001976a91466c9512832e733934858f4efaa4b708eda3efc1d88ac00a3e11100000000016900000000");

        Transaction t2 =
                wireFormatter.fromWireDump("010000000148f8ec7c5f8f177890356462aded52af5a2bf1cdff502a370d8557535dafec44020000008b483045022100bb3e570ec6da31297e83c238a8b321a4e1c649e1fd081bfc9bf58c295a3538e8022036982a1fb82ffd8a3bc274663ffcad0aa03ec6dcc906860b26148915dce642640141043b0126f59e00c25a3bf07462e5e3dd6fe6bfa8288f43021dade5b1402ea1c4500a04da5631b7dd568af0b208131e49f3862b51f16af74c39888dd2484ece126dffffffff0be0d14d00000000001ba9014c011414c286a1af0947f58d1ad787385b1c2c4a976f9e7187200b20000000000008517a011588745287a0987b000000000008007a011488745287a08f3e00000000000705feffffff008720145d000000000025aa012020bf5d3affb73efd2ec6c36ad3112dd933efed63c4e1cbffcfa88e2759c144f2d88760566c000000000003740087e0da8a0000000000019c604d2f00000000000cb0b1b2b3b4b5b6b7b8b9518700f65823010000001976a914c64e5bf30fc7cf458fe23f256f86548528d0521a88ac201d9a000000000002a591e0c81000000000000363516800000000");

        // 16 15 14 | OP_FALSE OP_ROLL 14 OP_EQUALVERIFY OP_DEPTH OP_2 OP_EQUAL
        assertTrue(createScriptValidator(t1, 29, t2.getOutput(2), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionTest13() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000062c4fb29a89bfe568586dd52c4db39c3daed014bce2d94f66d79dadb82bd83000000000004847304402202ea9d51c7173b1d96d331bd41b3d1b4e78e66148e64ed5992abd6ca66290321c0220628c47517e049b3e41509e9d71e480a0cdc766f8cdec265ef0017711c1b5336f01ffffffff5b1015187325285e42c022e0c8388c0bd00a7efb0b28cd0828a5e9575bc040010000000049483045022100bf8e050c85ffa1c313108ad8c482c4849027937916374617af3f2e9a881861c9022023f65814222cab09d5ec41032ce9c72ca96a5676020736614de7b78a4e55325a81ffffffffc0e15d72865802279f4f5cd13fc86749ce27aac9fd4ba5a8b57c973a82d04a01000000004a493046022100839c1fbc5304de944f697c9f4b1d01d1faeba32d751c0f7acb21ac8a0f436a72022100e89bd46bb3a5a62adc679f659b7ce876d83ee297c7a5587b2011c4fcc72eab4502ffffffff4a1b2b51da86ee82eadce5d3b852aa8f9b3e63106d877e129c5cf450b47f5c02000000004a493046022100eaa5f90483eb20224616775891397d47efa64c68b969db1dacb1c30acdfc50aa022100cf9903bbefb1c8000cf482b0aeeb5af19287af20bd794de11d82716f9bae3db182ffffffff61a3e0d8305112ea97d9a2c29b258bd047cf7169c70b4136ba66feffee680f030000000049483045022047d512bc85842ac463ca3b669b62666ab8672ee60725b6c06759e476cebdc6c102210083805e93bd941770109bcc797784a71db9e48913f702c56e60b1c3e2ff379a6003ffffffffc7d6933e5149568d8b77fbd3f88c63e4e2449635c22defe02679492e7cb926030000000048473044022023ee4e95151b2fbbb08a72f35babe02830d14d54bd7ed1320e4751751d1baa4802206235245254f58fd1be6ff19ca291817da76da65c2f6d81d654b5185dd86b8acf83ffffffff0700e1f505000000001976a914c311d13cfbaa1fc8d364a8e89feb1985de58ae3988ac80d1f008000000001976a914eb907923b86af59d3fd918478546c7a234586caf88ac00c2eb0b000000001976a9141c88b9d44e5fc327025157c75af73774758ba68088ac80b2e60e000000001976a914142c0947df1df159b2367a0e1328efb5b76b62bd88ac00a3e111000000001976a914616bffc03acbb416ccf76a048a9bbb974c0504c488ac8093dc14000000001976a9141d5e6e993d168384864c3a92216b9b77560d436488ac804eacab060000001976a914aa9da4a3a4ddc7398ae467eddaf80d743349d6e988ac00000000");

        Transaction t2 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0e041d244a4d0165062f503253482fffffffff0100f2052a01000000232102f71546fc597e63e2a72dadeeeb50c0ca64079a5a530cb01dd939716d41e9d480ac00000000");

        // 3045022100bf8e050c85ffa1c313108ad8c482c4849027937916374617af3f2e9a881861c9022023f65814222cab09d5ec41032ce9c72ca96a5676020736614de7b78a4e55325a81
        // 02f71546fc597e63e2a72dadeeeb50c0ca64079a5a530cb01dd939716d41e9d480
        // OP_CHECKSIG
        //
        // SIGHASH_ANYONECANPAY
        assertTrue(createScriptValidator(t1, 1, t2.getOutput(0), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionTest14() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000062c4fb29a89bfe568586dd52c4db39c3daed014bce2d94f66d79dadb82bd83000000000004847304402202ea9d51c7173b1d96d331bd41b3d1b4e78e66148e64ed5992abd6ca66290321c0220628c47517e049b3e41509e9d71e480a0cdc766f8cdec265ef0017711c1b5336f01ffffffff5b1015187325285e42c022e0c8388c0bd00a7efb0b28cd0828a5e9575bc040010000000049483045022100bf8e050c85ffa1c313108ad8c482c4849027937916374617af3f2e9a881861c9022023f65814222cab09d5ec41032ce9c72ca96a5676020736614de7b78a4e55325a81ffffffffc0e15d72865802279f4f5cd13fc86749ce27aac9fd4ba5a8b57c973a82d04a01000000004a493046022100839c1fbc5304de944f697c9f4b1d01d1faeba32d751c0f7acb21ac8a0f436a72022100e89bd46bb3a5a62adc679f659b7ce876d83ee297c7a5587b2011c4fcc72eab4502ffffffff4a1b2b51da86ee82eadce5d3b852aa8f9b3e63106d877e129c5cf450b47f5c02000000004a493046022100eaa5f90483eb20224616775891397d47efa64c68b969db1dacb1c30acdfc50aa022100cf9903bbefb1c8000cf482b0aeeb5af19287af20bd794de11d82716f9bae3db182ffffffff61a3e0d8305112ea97d9a2c29b258bd047cf7169c70b4136ba66feffee680f030000000049483045022047d512bc85842ac463ca3b669b62666ab8672ee60725b6c06759e476cebdc6c102210083805e93bd941770109bcc797784a71db9e48913f702c56e60b1c3e2ff379a6003ffffffffc7d6933e5149568d8b77fbd3f88c63e4e2449635c22defe02679492e7cb926030000000048473044022023ee4e95151b2fbbb08a72f35babe02830d14d54bd7ed1320e4751751d1baa4802206235245254f58fd1be6ff19ca291817da76da65c2f6d81d654b5185dd86b8acf83ffffffff0700e1f505000000001976a914c311d13cfbaa1fc8d364a8e89feb1985de58ae3988ac80d1f008000000001976a914eb907923b86af59d3fd918478546c7a234586caf88ac00c2eb0b000000001976a9141c88b9d44e5fc327025157c75af73774758ba68088ac80b2e60e000000001976a914142c0947df1df159b2367a0e1328efb5b76b62bd88ac00a3e111000000001976a914616bffc03acbb416ccf76a048a9bbb974c0504c488ac8093dc14000000001976a9141d5e6e993d168384864c3a92216b9b77560d436488ac804eacab060000001976a914aa9da4a3a4ddc7398ae467eddaf80d743349d6e988ac00000000");

        Transaction t2 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0e047d764a4d017b062f503253482fffffffff0100f2052a010000002321031ee99d2b786ab3b0991325f2de8489246a6a3fdb700f6d0511b1d80cf5f4cd43ac00000000");

        // 3045022100bf8e050c85ffa1c313108ad8c482c4849027937916374617af3f2e9a881861c9022023f65814222cab09d5ec41032ce9c72ca96a5676020736614de7b78a4e55325a81
        // 02f71546fc597e63e2a72dadeeeb50c0ca64079a5a530cb01dd939716d41e9d480
        // OP_CHECKSIG
        //
        // SIGHASH_NONE
        assertTrue(createScriptValidator(t1, 2, t2.getOutput(0), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionTest15() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000062c4fb29a89bfe568586dd52c4db39c3daed014bce2d94f66d79dadb82bd83000000000004847304402202ea9d51c7173b1d96d331bd41b3d1b4e78e66148e64ed5992abd6ca66290321c0220628c47517e049b3e41509e9d71e480a0cdc766f8cdec265ef0017711c1b5336f01ffffffff5b1015187325285e42c022e0c8388c0bd00a7efb0b28cd0828a5e9575bc040010000000049483045022100bf8e050c85ffa1c313108ad8c482c4849027937916374617af3f2e9a881861c9022023f65814222cab09d5ec41032ce9c72ca96a5676020736614de7b78a4e55325a81ffffffffc0e15d72865802279f4f5cd13fc86749ce27aac9fd4ba5a8b57c973a82d04a01000000004a493046022100839c1fbc5304de944f697c9f4b1d01d1faeba32d751c0f7acb21ac8a0f436a72022100e89bd46bb3a5a62adc679f659b7ce876d83ee297c7a5587b2011c4fcc72eab4502ffffffff4a1b2b51da86ee82eadce5d3b852aa8f9b3e63106d877e129c5cf450b47f5c02000000004a493046022100eaa5f90483eb20224616775891397d47efa64c68b969db1dacb1c30acdfc50aa022100cf9903bbefb1c8000cf482b0aeeb5af19287af20bd794de11d82716f9bae3db182ffffffff61a3e0d8305112ea97d9a2c29b258bd047cf7169c70b4136ba66feffee680f030000000049483045022047d512bc85842ac463ca3b669b62666ab8672ee60725b6c06759e476cebdc6c102210083805e93bd941770109bcc797784a71db9e48913f702c56e60b1c3e2ff379a6003ffffffffc7d6933e5149568d8b77fbd3f88c63e4e2449635c22defe02679492e7cb926030000000048473044022023ee4e95151b2fbbb08a72f35babe02830d14d54bd7ed1320e4751751d1baa4802206235245254f58fd1be6ff19ca291817da76da65c2f6d81d654b5185dd86b8acf83ffffffff0700e1f505000000001976a914c311d13cfbaa1fc8d364a8e89feb1985de58ae3988ac80d1f008000000001976a914eb907923b86af59d3fd918478546c7a234586caf88ac00c2eb0b000000001976a9141c88b9d44e5fc327025157c75af73774758ba68088ac80b2e60e000000001976a914142c0947df1df159b2367a0e1328efb5b76b62bd88ac00a3e111000000001976a914616bffc03acbb416ccf76a048a9bbb974c0504c488ac8093dc14000000001976a9141d5e6e993d168384864c3a92216b9b77560d436488ac804eacab060000001976a914aa9da4a3a4ddc7398ae467eddaf80d743349d6e988ac00000000");

        Transaction t2 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0f04b56a4a4d02c100062f503253482fffffffff0100f2052a010000002321035c100972ff8c572dc80eaa15a958ab99064d7c6b9e55f0e6408dec11edd4debbac00000000");

        // 3045022047d512bc85842ac463ca3b669b62666ab8672ee60725b6c06759e476cebdc6c102210083805e93bd941770109bcc797784a71db9e48913f702c56e60b1c3e2ff379a6003
        // 035c100972ff8c572dc80eaa15a958ab99064d7c6b9e55f0e6408dec11edd4debb
        // OP_CHECKSIG
        //
        // SIGHASH_SINGLE
        assertTrue(createScriptValidator(t1, 4, t2.getOutput(0), EnumSet.of(ScriptVerifyFlag.NONE), true).validate().isValid());
    }

    @Test
    public void transactionTest16() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // https://bitcointalk.org/index.php?topic=123900.0

        Transaction t1 =
                wireFormatter.fromWireDump("0100000002979bf5e04fb980f214c7b8f3ca28ebd1526fde456953210532e42246843e199f2f000 0008b48304502210081eaa77b0dcef66c0d0e62dafe932503cd8ab8bd83e4d132c9b42fd5a5be90 4202204a281c9c320f60b4a11bd7f162d8296d8246a13a43bc9e5e6fe831e8587bd8d9014104c55 f8edc724bc89b356bc1280f720b27e62839743e549d51bd9d537bd168b3b36f655b87f5aa492c15 eec23120f87abe36693830608a0f91b325a4f76570daf1ffffffffb1d3647334b5531f4831a48e1 fdda96472bd11b95140f0baf7fca5836854d45f2f0000008b49304502210081eaa77b0dcef66c0d 0e62dafe932503cd8ab8bd83e4d132c9b42fd5a5be904202204a281c9c320f60b4a11bd7f162d82 96d8246a13a43bc9e5e6fe831e8587bd8d9010440c55f8edc724bc89b356bc1280f720b27e62839 743e549d51bd9d537bd168b3b36f655b87f5aa492c15eec23120f87abe36693830608a0f91b325a 4f76570daf1ffffffff010d787000000000001976a914fe9c3e50dd8a5263571764dfa9e80300d1 5f612188ac00000000");

        // garbage in coinbase
        t1 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff07062f503253482fffffffffd809990900000000001976a91458a08ccf0fb238718d3904714000b17a81f0060d88acc7a80900000000001976a9141823ec0e0cf4c185d336ad3c145968252f86714e88ac39340a00000000001976a914b028b1d35cb47a221649bd0e669610ef8b7e3d7588acb0dc0a00000000001976a914236a0ff70dd5f81909fbf702228b1d8ca37ec7f488ac72090b00000000001976a914523245d3a539a64d227126049cd2f2f2306d22c988ac7ee00b00000000001976a9146eaab7859c87bdfbc2d34edbcd74f316b6fc684d88ac03380c00000000001976a914c3a2f3551409af20a481ecfdb03fd2e3848343d588acfe520c00000000001976a91494ed80858686f1d55fecd89ed012a260464e931388ac53610c00000000001976a9146e1fbf86573b5b5064614d043c203d1d5c9a382d88ace7e81300000000001976a9146deac45b7f3c9c1a44a6c159286b74fc67cc0ecc88ac573a1400000000001976a9140e270d5b535ee7267520bc582f07892bf0e10bcc88ace8ca1400000000001976a9149e7c992cfc45cc45fd1319a2693bb1c87613173988acb8d71500000000001976a91400addb85fd3939406c00d495deedf5a5a868f06b88acca671600000000001976a914f952df526125e8073ce3d53bc918d6d10a10b52b88ac84f31600000000001976a914336d7d0d979311d9e9a3030cacb096d1a3845a0988ac7f071700000000001976a914bd9cea910cf48890afe72a0f5e34cf841957243688acbd5a1d00000000001976a914c25086c6f75c872dd007ae905c5570725b6a8c6888acdf201e00000000001976a914342ad939a4c5a4bc54954d667c0a6353f60b31dd88acdb541e00000000001976a9145119ed947c850766e6cf4460aa03511c0f56546088acc4bd1e00000000001976a914f5be4d5ec4957f26ee629b9a768598874a36ef1a88ac3a111f00000000001976a914c1dee37303a3cbbada7822fb361249938d01badf88ac90551f00000000001976a914b2478ceb2e04f84a25e317ec2d8d8acf9c192c8588ac23561f00000000001976a91470975478759bf39d5f50cff56a7eebe7c918468588ac2a941f00000000001976a914adfc4367b204d75386c83775e97164b987d9297d88aca3b31f00000000001976a9149b07dc20fc82f62c124e33daca18b3044023b37a88acc02d2000000000001976a914165618e06f1aa8ec1a7c6bf6050efc86a64bd69288ac38ec2000000000001976a914fa42bfd7489a301ecd6ba9974bdad44e69091b3388ac7d382100000000001976a914cf70f86013e29f133fdcc3c602e034338a19a90d88acd1612100000000001976a914e349addc2e673c575e1a517e3ee68ef95a5dc04588acee492200000000001976a91408d0008497c7d48e222699e1b38b663e9531849e88acabf92200000000001976a914654a6c1d1615ff3dd9832e37f55ebbf29f165b1d88ac6e742300000000001976a9142912db83f350e3f3be2aeda741a4c66bfeeb1a2e88ac7c782300000000001976a914bf7a9d0ec051a1199bafed03b14929e9af572e3a88acef482800000000001976a914efa4b62bad26990e2d57c140669932419259640088acf82f2f00000000001976a914dbd93093ff21bb585a5552eeadfb677eed3a183188acc1342f00000000001976a914c52c18005c5773e5b513aadc31c8a1fed3fe7ad588ac6f0c3500000000001976a91407f021706161902974f6d62eec4612a77ceed20a88ac49683500000000001976a9145d79c60441d02be3fe14028982be5ded8b8f283f88acf9ba3500000000001976a914f61574714a2ddd0bd44601e7a5be3df2ffe0f05688ac38c83500000000001976a9140676d03b8b826effc748a062241fd861f4fab58388ac855d3600000000001976a91419f940ea118a0cca01f01613f616ce2336c5bbd588ac187a3600000000001976a914929beb6c43423f52a86f0c66eeae26c916ca895088ac1bd13600000000001976a9145bb1e305c133d2550cb18b2673f813b0dbbbcbc788ac0ddc3b00000000001976a91468c80b7cfb2c8a5a5b9d094f4cbf6980c03f2a5788acb8b83c00000000001976a9144ff8472b4bf4482dd19dd530e8f6bae3c337241b88ac84523d00000000001976a914cf2013b84b2fdae45d89554268635a76a4f0e8b988ac950e4000000000001976a91448d3724b97f74205c878508186afc1d34547bbdd88acb84c4000000000001976a914a8d218c4c8086d6f8709040486c02ff494ef973988ac06814000000000001976a9146685f0c9c4d9b89f56997d028c5b0fc9788f8ccb88acbfef4100000000001976a9143ff6f5b082add5a1d476fe91eb0e5601b659f0ce88ac3a544200000000001976a914161199f7bbed0222b94d03dcdd24b15a705f5f1188acee9d4200000000001976a91426bad6b6f1079452cd954471d58a641dcd20abb888ac29bc4200000000001976a914634c978b73d76648bcc9aac7c64e385a80ceca6b88ac5ee14500000000001976a9147b78fdbdb59180d031ced94791e07db1f57b6f0d88acb3314800000000001976a91406346c4a74b9ba1526b92d50d016205e985fdab088ace2734900000000001976a9140a57e95b1bf862cf3c03b1c12e602f0442ba71fb88ac17de4a00000000001976a9143e9e24b602b382961357ed807eda5445d080678488ac5cc84c00000000001976a9148c9360e25a76046c63816a38476253821850035588acf63d4f00000000001976a914ae84e741f3242a9e211bc36ceebcecff23aaea8c88ac6b285100000000001976a914a70171eda8e6a39555d37f78b919ce90ddb80fe188ac7e2f5400000000001976a914112aeab933e44d1dec9954f254d982d1e6ff4fb688ac61505400000000001976a9149e964bfd4d25ad4e168b50f86ed2a2f32b5203fe88ac86be5400000000001976a9146f7b51c376fd6dea8edb2007d049f60bb6071a1488ac981b5500000000001976a91452bb0b5ed19df0e50c093a0bd448d5cd709c8d6088ac2f035600000000001976a914827b7b5bc4b1e48fe51e1c451c361470ae4318b688acc34d5600000000001976a9145b1d27a893325b2134032f09362ad27c9ccd929e88acce685600000000001976a914a394e064dd1dd4de00f795930ff4e647a227006488ac5d415700000000001976a9147fcc2253397dcf99e698167d18904a06e57e0dd588ac68c65700000000001976a9149fa953a138a5cbc7892e49c58b3fbc36f8e2f76288ac60025900000000001976a91458aa99f298e42f7b68b01b964ae992e06360203a88ac856b5f00000000001976a914ffb7ff00b2906d90600da8f43012acab6073268288ac29fa5f00000000001976a914d090e2629917fce83d517b22f65b1df732a7317088ac8e556100000000001976a914b59e43784f4d3d2e2535396d11ce80db7cc561ec88acf6826400000000001976a914e295305eb7e0a8e86f79fd0c5125a1b5c44c6aa588accc456800000000001976a914a7eeccb58287bdb1612abb137d6a1629607e97a388acaef96a00000000001976a91421bd2c39039c8d2fbee1bd069105de8599d9c26f88ac36fc6c00000000001976a9146273ac9fa3bb2d77e2b5f2918b4dd1d6d685b17188acae5f6d00000000001976a914d3d5319453ab8f8d5e0454ea2a140a7859e75ca988ac107e6d00000000001976a91464eb5bbd8ab5393cc2e22e07ed63d6ebef5a8e3888ac4f7e6e00000000001976a914aa19c3daf361b17205f46fbe0ccb526c5a9d84e888ac056c6f00000000001976a9141dc79b2c61ba9e2381fedb4a63f54ea8cf950fc888ac628a6f00000000001976a9147f76ed0f250d98df17ddcb127a6f68e62e9e626e88ac98327200000000001976a9140f2083fc41e56da30e070a9336ffcd4efa1f236088ac34d37300000000001976a9142262fdce435f8eff2d28069516eab69ef440fa9a88ac8cd07500000000001976a91453575f3e74ae6b793d562db660a5cb73a47a860888ac4d8f7700000000001976a914081400ca7d6f892e5a7f807e88b3a1bd7d32817e88acb57a7800000000001976a91475ee0cbfaa5fe7e0fc3aeb87a3af78ad6f0c193a88acd3db7800000000001976a9148ecbf1ae458701c84a7eecf6dc3eccf7aace2a1d88acb5fd7a00000000001976a914c008f4c184c98d0647002af1bd47511a4017dbd488ac44a87c00000000001976a914af321ff46aa4a569b7902bf92a66ca0f1c5446b688ac091f7e00000000001976a9149543b150b7f9d1efb27761b72d027701f4f22e6388acdd0b8500000000001976a91404a64323ddc9c0385aaeebe53eea5c8f2eed6cf588acd1568500000000001976a9147c755030e4fe6386a9cb7aff8d6b3a5ed632d7ea88ac2cbc8500000000001976a91436c1b0cfc8081d98e0ec1682af913757ef71679588accb0d8600000000001976a914d1b47911a42a2d1c4d26c42a1b94ba626780e86288ace9f28900000000001976a9143a7ab2cefaeb6345aef29bae00530548f65bc3fe88ac8fe88b00000000001976a914496f96ef01f36f5f9ff675b23f42e261aa7b951588ac26d08d00000000001976a914291d0fc95e11d0e21244cf31d2ff0d0a3b9d73f988acc3348f00000000001976a914754f36491050c2cc39925b15182e0af97c9f724c88acce1c9200000000001976a914e205b664ff00fd8be0a78cbee242d60f83af69e188ac0bc89700000000001976a9142b1d993b40652df56df573eb1ede526a23d8ecad88ac6d3a9800000000001976a914ced2d24d8f9a12afac41ab4ac2aa3dccc5c2412588ac1d5b9900000000001976a914c5f162f2b5446b57cf8f03e8791c65fb10592eb188ac51ce9b00000000001976a9147c5bb43936e3cf534581664fde3aa9c7ad38a28088acc8d99e00000000001976a914553a6dabacb3c25d711bf3cdeaa3891ec481227688acb0e5a600000000001976a914309467cf0306dd5a9d782132ae020215cc6a3e7e88ac426aa800000000001976a914e1fe17adaba3338ec693468fc797662b005deb6088acce81a800000000001976a9148788d4489e1a43578a6c0968b0d8f280be6ff16c88ac372fa900000000001976a914dd7a879a2330eaddbff7ca726c4f35442e9ba3fd88ac1c3fa900000000001976a914f19595088875fe2c80a118e4c7e24a82370cfd8e88ace105aa00000000001976a914499fb717f14835af593ff2b3dbac02d0de4578f888aca8d6ad00000000001976a914775e5a64eb13041a3b1191ceac7cd192a61fc9fc88accc68af00000000001976a9141cf73ca7f2075df1984e91a57983aa75444de0be88ac709cb200000000001976a91494b837cd58ae483b054024f3399572b79b3b6e9388ac53c3b200000000001976a91414735795fc29fe3fb0983be8218fed6eba84c5bf88acbc09b800000000001976a914763f533bd3572d668fb259b65a185813750ced8f88aca710b900000000001976a9145479d3a0114c8cddf9db7a9968c2080310099b5188ac3343bb00000000001976a914f770bd8837733b0e33eb0817ccc087ced11901ca88ac5d99c000000000001976a9147f92bc474f4d80ac06c8f02d57915bf550c8447b88ac05d2c700000000001976a91444ee365dc5924fdb46b8db0db9f8965f1528390e88ac75e4c800000000001976a91468212a9bc5becbc88fb79c69e72a55ef80049d1188ace177c900000000001976a914ac29d7fa0bdb2a63c7f1a28dae32895607f45a2e88ac0947cf00000000001976a914ee3b1fd4dcea91460a2c55ae8193d6e4836717b688ac636dcf00000000001976a914e2561b22f7f00045e6dbdfc58cf26d15397511ba88accb82dc00000000001976a91467384fb32cb473d7e1c969b7fcd673633430d57788acc6e6dc00000000001976a914bc589b3115f57c322c367f9e044e69e67934cf4588ac52d9e900000000001976a914d7fe10226d6ead808edd7d817ef01e11ae9bbbd788acf1dff100000000001976a914093064e56638d7d2b7f122d073f36f2adbddae3988acac34f200000000001976a9140cb6f1c0e7e968a543379bb5ce61d388c127a94488ac9944f200000000001976a9149fe10fdcc48e8130b21270831073fee3f855c5b288ac5b6bf200000000001976a9147c524c56243616edcf2f2e973420e293c82cbe2388ac1a4ffb00000000001976a91475491129d19751d0c0e984b510feb14467523c4388ac80d0fc00000000001976a914eea32a085bbb4f153e4988b24ef28faf71fdfdba88ac1c7a0001000000001976a914d20646b53e3fe6e2dd53451c60920e920e6ef5de88acc9390401000000001976a9142261adc51bfea49e31684aa66991ea4e944ff8af88aca7380801000000001976a914d28e1e827c4f2ce5af3a57adae0eaf99b73acd3888ac95071101000000001976a91488985e1db043de28fbfd10eb347f3b070882b8ce88acecc71201000000001976a914444d9c6f323cce2424513849b85f1549ab1f789088acbee21401000000001976a914015dbc353e51ced0794cd834666e715ff649ee6988acd3951601000000001976a91404947ccb0400df80b82d52026cd3e6ea6a47412988acc94d1801000000001976a91495ab2e374619aad24375d0e374ee697e9075cb5488acce9e1a01000000001976a914f8f522d48137b10b0db4b4b702ec0e8936f69b6688acf5551d01000000001976a914a324583efbfee0e490947670012976bf8cf7700c88ac97b22601000000001976a91491f076086d0cd7c63b8c1e7329094a205b8e84c288aca05e2701000000001976a91410c6c0640a92b05783677726eb336779a55aa7f088ac6c292a01000000001976a914dabbac689586b9eb25f423672d160c78709553d988acb37f3001000000001976a91449e68318f8f90529eb95a477eeb5a10405c6c87788ac338b3201000000001976a9143ae0c73d66407ddf23f7af4143e00f0d0a9c387e88aca8c33901000000001976a914a5aef750d2240b740172e349e7a4eb6ad2b608f088ac0d293c01000000001976a9140efa4c82653bf5f03ace67c049f946e0133522fb88ace3c73d01000000001976a91480207c9f445b47ec5e2a4607861ea2d8fd87718188ac28174201000000001976a914afaa77471e0dcec3f4fedd39e667737bf7863d8988acd03c5a01000000001976a9140af27cf6a67f19572ab58189994866937284e13f88acb3165c01000000001976a9147eb1aff02a2b79c7ec436371c97e40dacfa2191888ac297f6401000000001976a9148c696cde3168a956d7cb9bdeff6d17b3c534eba988acbeea6401000000001976a914e8c1fe2df2ffb21bf2109a335ced56836985f76688acec606701000000001976a914647484ab81427b30333eeaae714ba08a0980a83288acf0116a01000000001976a914eabe80ff2219adf238cee601eb86296760534d7a88accfc97a01000000001976a914b5a937c6b8c90e15a7baa531fc5691600daa92e288ac20277b01000000001976a914bcf82a713a320942ce47e073787b48e73ed21bc388accb4a7c01000000001976a91410519f2852d61880aa241691187df0a2fab8631388acc6f87f01000000001976a91411a8919854547989c7338b01634c77fc4b3c8a1e88ac44728101000000001976a914e8b915b82ece7ad4dca24bf1524507b04e0e95c388accea18d01000000001976a914e75c0a4d431629abd09a64ef76739510e560c75288ac291f8e01000000001976a9149213126ed991551e4fdbce1bb0d1730a7ef3f83c88acf8fe9301000000001976a9144a54a287afa5d094f233846407fba86b19dd84e988ac53f09701000000001976a9140aca7ad87e8ed090e2d8823028ea1b2253f2db9788acbbdb9b01000000001976a914d60a86e3722e05fc1edde4b3f18bf37c133169ec88ac5774a901000000001976a914a2555d2d1a9702a9c550c09e61d6573c510cbd4d88ac15cbb401000000001976a914cd00e7ff5166ce029e756410bf0429ef56e2751288ac11acc901000000001976a914b5256af108d3004009075175d0ffc1d3afbd807c88ac9a66dc01000000001976a9145758ab9fe00ab3eaec5c654984eac52c4d61e24a88ac57bde001000000001976a914596c979cc90c686cbae454d1da79af87e724ebc288aca30ef201000000001976a914730f40ca59fa6c61a140ca07db33f33c143a649488acd843ff01000000001976a91401f3baf2ce3015e7861903bc20ac9956acc6176788acd0750202000000001976a91489ee15a0ef1f1ad8828e9d5db115e8019a82ed6e88ac19c90e02000000001976a9148fcb2b8e47a7076014aee4c0d4f44ff04dd98bc688ac43b81e02000000001976a914107ed75084c97e366c588ca6fd73c7b0f23b49c688ac37b92002000000001976a91458624438e431f9f2c842fbbe6aaf63e3825f4ab388ac1fac3402000000001976a91443d551c6996698937e83e376b31005659a95d7f688ac54703802000000001976a91461e02dd998e188193288e7744f9775f57314a2d388ac07123f02000000001976a914dc8b7bfa664a3f1e6fbf1912387021607500224688ac30f44f02000000001976a9140bb327b3cfeef60671df2a29a7f1d5e8f60d4daf88ac8d5f5002000000001976a914188ae4c5c9f09bd7deb65dd113ac5ebf50207bb088ac5a406402000000001976a914f025c7c67f6b5abbfd852906b6abea1f557ff75088ac9fdd6702000000001976a914160767ea477550a19b9fbb7d91cc3047e8adac3588ac683e7802000000001976a9144cebba52948706883b6008125c52dd4806f5c2cb88acb45c8602000000001976a914580d858c05806ce0a0c4d5c725fe70f95aeeee0688ac955bac02000000001976a9142af1c428ea03d0cd37c6b64db3afacae5f505a8388acaa63b702000000001976a914a460150efc3818e11f28d96e4ae69b1c60d77b1a88ac6edfd402000000001976a914c168323170c52e9a28c4b069da735bffe8d651ab88acb53a4403000000001976a91488dd4ea28568d6b1de9381acdf81320f960c84c788ac50a74603000000001976a91466d60d45b8aa75b88fd1bb4d8b8a7950950dc6e588ac22d26003000000001976a91418a99959bce205725051f5ead31c4b88379a4fcb88ac0f5c6303000000001976a914848777c55f2caf1e5576c563eaf7a4f14e29814d88ac26c68403000000001976a9141af88a1841569c94c6fb277cab26d635cfd6b0b788ac0d788803000000001976a91472359aaacfd5b2eca4ea22a1a22efcee0c0c524388ac9d85ed03000000001976a914c03ad4624c47049467745c095d17f29b95db8fef88acd722f203000000001976a9149b0551defdcbf45fd256b40557bcfb90d39ba73688aca07a0a04000000001976a9140ff716e46090abb52d53a62a38a27fd0b17d45df88ac8f89c704000000001976a914a82afdf9e665b3354ecc63d6b6a21b5073b1d6c188ac86bddd04000000001976a914ba0b189cd808746a1efc9921665dfcfe301222e288acc8109705000000001976a914468f60bf644a3edb502a21ad706375b91330df9088ac7d21f405000000001976a914e02e2d4c5fb28a189538c038aad5991a57a09ffa88ac999d3206000000001976a914faeb176dd2321e0c73e4276fbda3370507750ebb88acbe9fed06000000001976a91422232ab8c430f4bd1c2520f6a46b4d7fa0a90d9988ac2f311c07000000001976a9144a799470ae0ef7b84dda0bf8a7b92e9bac4ebad888ac157aaf07000000001976a9140e395d4568e071968db0e8e775f891021d4c82f088ace66ff007000000001976a914fa183499bcf8cc1bae1763cf50fd61f9601ec83088accd8f4d08000000001976a91427cc7e0def9fb434d51d98ee523bcc1e7514390688ac98f99e09000000001976a914838151b12bbba4e843aa878ad4cabea5ddae016488acaa299e0b000000001976a914bb1aa9cc9cf2c9f4133a0ded15328650bbffa2a188acca5af80c000000001976a9149c7a3ddca3446a6e36160495a78317fe1cb8f1cc88acfc264412000000001976a914538790f005572bc8666fa3c80e3cb07211d4ea4a88ac662ee60000000000434104ffd03de44a6e11b9917f3a29f9443283d9871c9d743ef30d5eddcd37094b64d1b3d8090496b53256786bf5c82932ec23c3b74d9f05a6f95a8b5529352656664bac00000000000000002120a698c8165b47d0cf962d9d54effd2ecfc4ff8b944bedce4c9062291e1ae22e3600000000");
        t1 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff2103880703062f503253482f04634d5150081000001300000002072f736c7573682f0000000001e0435e2c010000001976a914e8e6ace10f10ce5ed479c7188c9b4061e53aa90688ac00000000");

        t1 =
                wireFormatter.fromWireDump("010000000123ba84a7ce8ab2785a838fa9ca50ad395bee25aba38dda28336cc337d08a599e000000006a4730440220320a58bf09ce578dd2ddf5381a34d80c3b659e2748c8fd8aa1b7ecc5b8c87665022019905d76a7bbc83cbc3fb6d33e7e8aae903716206e9cf1fcb75518ce37baf69a01210312c50bdc21e06827c0bdd3ef1ff849e24b17147f9a6f240c4af45bd235eb5819ffffffff0102000000000000004751210351efb6e91a31221652105d032a2508275f374cea63939ad72f1b1e02f477da782100f2b7816db49d55d24df7bdffdbc1e203b424e8cd39f5651ab938e5e4a193569e52ae00000000");
        assertTrue(t1.getOutput(0).getScript().isMultiSig());
    }

    @Test
    public void transactionTest17() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        ExecutorService executor = Executors.newFixedThreadPool(4);

        List<Callable<HyperLedgerException>> callables = new ArrayList<>();

        // Malformed public key in multisig
        final Transaction p1 =
                wireFormatter.fromWireDump("010000000123ba84a7ce8ab2785a838fa9ca50ad395bee25aba38dda28336cc337d08a599e000000006a4730440220320a58bf09ce578dd2ddf5381a34d80c3b659e2748c8fd8aa1b7ecc5b8c87665022019905d76a7bbc83cbc3fb6d33e7e8aae903716206e9cf1fcb75518ce37baf69a01210312c50bdc21e06827c0bdd3ef1ff849e24b17147f9a6f240c4af45bd235eb5819ffffffff0102000000000000004751210351efb6e91a31221652105d032a2508275f374cea63939ad72f1b1e02f477da782100f2b7816db49d55d24df7bdffdbc1e203b424e8cd39f5651ab938e5e4a193569e52ae00000000");
        final Transaction p2 =
                wireFormatter.fromWireDump("0100000001ab9b9c1610dd8dce68fb8d1d787537421a8610364d9f6907360b33739c464432000000006b483045022100dcd533f206756c83757bd0738905799dd0c7f505c22c567641b1b35573a9b24b02204c3773f60752ea67809aa32eb0a07c0f16bcfe073c99e84c8c30a328fa14874c0121031c9bfff835236f589ba409b364a9d2c392971c053cdfbbac9ccdd9f30eabb15bffffffff01404b4c00000000004751210351efb6e91a31221652105d032a2508275f374cea63939ad72f1b1e02f477da7821004f0331742bbc917ba2056a3b8a857ea47ec088dd10475ea311302112c9d24a7152ae00000000");

        final Transaction n =
                wireFormatter.fromWireDump("01000000025718fb915fb8b3a802bb699ddf04dd91261ef6715f5f2820a2b1b9b7e38b4f27000000004a004830450221008c2107ed4e026ab4319a591e8d9ec37719cdea053951c660566e3a3399428af502202ecd823d5f74a77cc2159d8af2d3ea5d36a702fef9a7edaaf562aef22ac35da401ffffffff038f52231b994efb980382e4d804efeadaee13cfe01abe0d969038ccb45ec17000000000490047304402200487cd787fde9b337ab87f9fe54b9fd46d5d1692aa58e97147a4fe757f6f944202203cbcfb9c0fc4e3c453938bbea9e5ae64030cf7a97fafaf460ea2cb54ed5651b501ffffffff0100093d00000000001976a9144dc39248253538b93d3a0eb122d16882b998145888ac00000000");

        // parrallel script eval.
        Callable<HyperLedgerException> c1 = () -> {
            try {
                if (createScriptValidator(n, 0, p1.getOutput(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid()) {
                    return null;
                } else {
                    return new HyperLedgerException("script is not true");
                }
            } catch (Exception e) {
                return new HyperLedgerException(e);
            }
        };
        Callable<HyperLedgerException> c2 = () -> {
            try {
                if (createScriptValidator(n, 1, p2.getOutput(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid()) {
                    return null;
                } else {
                    return new HyperLedgerException("script is not true");
                }
            } catch (Exception e) {
                return new HyperLedgerException(e);
            }
        };
        for (int i = 0; i < 20; ++i) {
            callables.add(c1);
            callables.add(c2);
        }
        List<Future<HyperLedgerException>> results = executor.invokeAll(callables);
        for (Future<HyperLedgerException> e : results) {
            if (e.get() != null) {
                e.get().printStackTrace();
                throw e.get();
            }
        }
    }

    @Test
    public void transactionTest18() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // send to OP_1
        Transaction t1 =
                wireFormatter.fromWireDump("01000000027aa7f9172660e38236b3bb97830c0b79a6e843ae83145d8707b9b8f249e7c470000000006b48304502207b3a5fc309e495b93b4f9136c5780c8c03a44eca5b1d1bdc867d85757f5064b80221009a0845c3a48e5bf1881c760d321fc67ad56daaee608c6945c90996b23a08006001210353f9a0f2c1050c22bc2292b67adf7dae73c6917412a77b29925107fc795ba621ffffffffbce7839d77c264156d6724c4ac9a6be344b00c7cbb19214681c5e5f819dfc1bd000000006a473044022066663219d304b747b69c4c987c2a7d3b9de0fd467a79ce984fc08ba79a78d51002207d6641b1fcd3314a8b8ea957f3dafff689df29bd39f2c6b0c6a584356142154c012103f81890f43dbf0e38f734a8b8f0da9bb1ede141e34bf8793dda153c1beda1cbfbffffffff0100e1f50500000000015100000000");
        assertFalse(t1.getOutput(0).getScript().isStandard());
    }

    @Test
    public void transactionTest19() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // nLockTime is a block
        Transaction t1 =
                wireFormatter.fromWireDump("0100000001ef5b2488af5105d146edef7667b5951ffd84e3e03fd310eb7e793a97ee0b05df010000006a47304402202ba8dab5020323f99c496b968f500a07e60af5ab1f831bacc4783dcdd1905adf022027fc4ab402794cddb6727dbf11364a7d2c8d4d8c80da02b5fea092f2b29501bd0121024820e30ef1c454426c7049927cd65a704a086df2807677a142cd76223d0b401e00000000018035bd00000000001976a914fb99bed1a4ea8d1d01d879581fce07b27ab5357f88accb480300");

        // slush voting for PSH
        t1 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4904c93c081a029010122f503253482f42495031362f736c7573682f522cfabe6d6ddad6fdd3a31f068f9e76bcca6c39cafb2ea3d27ec81440c5bc76570fcdb364130100000000000000ffffffff015066282a0100000043410413bff315042a434862bb9438f2f236e70b7bb0ffbef562f57e4fe527005ccafc0a3f6173530d544a4f08a73c35fe73134d41fe9ee984437d71a967ce6a9e9a57ac00000000");

        // junk in last output of coinbase with 0 value
        t1 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0c03acf20200062f503253482fffffffffd7f7720700000000001976a914a33aa2cc1bd5f1f7030ddd818dac5c62f8feb43988aca0c60700000000001976a91494af9d55e3ee75aa3bc061d045184cd1d3d3e2e388acf5ce0700000000001976a9149288bde5d81e219e9a99e4e61a8fb2b280d0250388acd3d70700000000001976a914f624f25fb3355981bca750fd2562833d28e8dc6a88acd2e80700000000001976a914c00f5ec91429828a0daced1d7aaafe5b5b14ce5088ac04380800000000001976a91449c85ec220eec894e63b705e889b0932ce49001c88ac83410800000000001976a9140ace524c5951dfe0230169f176defdb9fccb2dc288ac2c510800000000001976a9146543d74736402525247084a79cf2ab80d581cafd88ac21870800000000001976a914038f164e696958961cc9af19b23704d631c742b288acd9bb0800000000001976a914fc87b6ccd838e5cd2376b722cb347c7d2b14f65c88acf0bf0800000000001976a9144eb26fe06dab796bd080e0661816123fd1f4098c88ac3a170900000000001976a91446e5ef2b0da687a32da9b7df77903e4da2dfb78388ac4b350900000000001976a9141f91d93c73bbbd2506d3a0897d127c5de539fbe088ac52c80e00000000001976a9145233140a06badc7c4e8c3aa8412a16d98d93d24c88ac99cb0e00000000001976a91450398e2abc80a54f3a0d9c5130ddb3cab2c093d688aced280f00000000001976a914abbb30ed932b7ad87a07a5009eab0e678737804d88ac20530f00000000001976a9147e1ce11b9186cf0ef3cd6f95cd0c085875e4715c88ac696c0f00000000001976a9141fcca5d999a2d92b0a512f33dabee81af91af8a288ac00710f00000000001976a91405203ad958dcc39d638af1cc33455ec1733ae02b88ac2ba30f00000000001976a91403dd220097e6be6676ee974cac5b3f458fdf051088ac46d80f00000000001976a914e448e4a84e7f66b1a025d06cb85039d7d1a09f4888ac32fa0f00000000001976a9141df72181150cbf54e0a7a496975e9f7c64e3841788ac82041000000000001976a9141ccf6cd59336e2471c7da65700d20d6106c9be9288acd2111000000000001976a91474c2105825da113e5a21fde9b0d557eb3e6185f988ac4c9c1000000000001976a9143e226ba2d1d7d1fa57dda6d1600e37ce9d1f5d0188ac785c1100000000001976a9144b47eb499f82d9c6927c93d63937332ca2cd602a88ac4a681200000000001976a91411b4bea74e10a61be25e013529c32a1ae2c8eb1788acf8371800000000001976a91483d37a6b478934daaa0b5b5ff2846f453bb2282e88acd6641800000000001976a914e6b86295a4af9fb32e5051d405f950617c2b329988ac3c6f1800000000001976a914625bffc6c22886c997cc87c8384a5c874e0a3f8b88ac4fbe1800000000001976a914626cfbd1437fb8d623b339c908dd2f966bc51ec988acfaec1b00000000001976a91418a99959bce205725051f5ead31c4b88379a4fcb88aca2a21c00000000001976a9142ce4bc0302990935f84afca3ae1a0fc8877953a788acad3f1e00000000001976a914097965f4d59c01c142ec8cffcc74f2ada392cb7388ac7fd51f00000000001976a914c3a2f3551409af20a481ecfdb03fd2e3848343d588ac86e72000000000001976a914e295305eb7e0a8e86f79fd0c5125a1b5c44c6aa588ac12182100000000001976a914fa42bfd7489a301ecd6ba9974bdad44e69091b3388ac5eee2100000000001976a9147f829605652e03ae9eeb8c37f8f2eb067192efd288ac4e032200000000001976a9145119ed947c850766e6cf4460aa03511c0f56546088acf8252200000000001976a91454a22bab38b27589d32e2b9c8c2428905dc1702c88ac3c9c2300000000001976a9147c651c6877484d6ae06cf431cc5913f25a680a6088aca9232400000000001976a91458a08ccf0fb238718d3904714000b17a81f0060d88acdc512400000000001976a91409c11b45c26ebef128252033da7605ff23684cca88ac42612700000000001976a914914015f45fa9294e8917f4ef446345dc0709d43e88ac3e912800000000001976a914e75c0a4d431629abd09a64ef76739510e560c75288ac97382900000000001976a914adfc4367b204d75386c83775e97164b987d9297d88ac59852900000000001976a914cf70f86013e29f133fdcc3c602e034338a19a90d88acdb892a00000000001976a9147487fe51c0a6a2188317835d21ada968ffb2e35688ac94952a00000000001976a914d09290401eab45f587f2d17df43a92d749da426c88ac9cda2a00000000001976a914fb3f592117caa2652e93e31e82684875e071dae088ac6bb32d00000000001976a914fd6051c4a1bc8a7c45a3e8c9c0958f54ff9acfef88ac4f8c2f00000000001976a91433c1b0a94ee653f820adc8a0ebb46f3e03b9787c88acf49e2f00000000001976a914254b46d0e611b7e606afa257b2eda9a6120485d188ac4e1b3000000000001976a914b527bf3a1cc2a7f86fc72ee42763069821ddabf388acba1c3000000000001976a9143547356c950e0bd272041c8a359f33f4250364d288ac04aa3300000000001976a9140676d03b8b826effc748a062241fd861f4fab58388ac48673400000000001976a9140d7f6faadd20c3c118f016798611a55c33fd0ca588ac40b13700000000001976a914e1fe17adaba3338ec693468fc797662b005deb6088accf003900000000001976a914e349adcc7bab6e87ef16b7980c052c893c40e98588ac2e7e3c00000000001976a914dd7a879a2330eaddbff7ca726c4f35442e9ba3fd88ac17a33d00000000001976a914f1eabd60cc0ecba0e98ccee1bfbd4ba13aec174d88ac2c1f4000000000001976a914889f8ec1d23a3fa4fc9324f94ac3f99c6fa9a8b588acef354000000000001976a91450b4b36a79926000b7639779b2e6983c7bd7663588ac36384000000000001976a914cf2013b84b2fdae45d89554268635a76a4f0e8b988ace3cf4000000000001976a914181ef6a86b05fb565fababe3be4600747e530da088aca4114500000000001976a9140a4f4dbf17aa053f327777c40e7827a6ed632ccb88ac98164600000000001976a914161199f7bbed0222b94d03dcdd24b15a705f5f1188ac4e294700000000001976a9144ff8472b4bf4482dd19dd530e8f6bae3c337241b88acd67f4700000000001976a91481de5d6e3c8634bcde74f2e6b1d8ea5bf5f82e3988acf4cb4700000000001976a914ff85800ad6297e8ce459a04f6746477dd01f570b88ac68df4700000000001976a9147ae1dcf9f1d50785536ea8198b188d73804a78e088ac05544900000000001976a914f9b0775ffb55b91b46b95e2f0bf14b1c66756a5e88ac971b4a00000000001976a9141a064df58cbea8ab85675e41a302934eda25bb8088ac43504a00000000001976a914d090e2629917fce83d517b22f65b1df732a7317088ac155e4a00000000001976a9141dc79b2c61ba9e2381fedb4a63f54ea8cf950fc888ac76c84b00000000001976a914bd947691320e51efb5db70cb70ec78d12840153988aca4644c00000000001976a91440d8a94ede2199747c3dd86fc8a0e6001b5d5df488ac86974e00000000001976a914ebd7d33db16385fe4d91ef1c25c36392d03ed62088ac82a14e00000000001976a9144a54a287afa5d094f233846407fba86b19dd84e988ac53485000000000001976a914d1b47911a42a2d1c4d26c42a1b94ba626780e86288ace7675000000000001976a9148012d70eea0df45f2138f39bb20db8c60a2a421788ac87645200000000001976a9140f198b4b4a80e33f0d186d370dab582aef4a2a5d88aca8a65300000000001976a914006e5858de7a93f56c168e77e2acc273fa09ce8288aca5e75700000000001976a9146517208b1bab9b0bed4d0b3dd94269c68829322788ac6ac85a00000000001976a91406346c4a74b9ba1526b92d50d016205e985fdab088acb1696000000000001976a9143e9e24b602b382961357ed807eda5445d080678488ac7e3b6100000000001976a914f1f630e1649d4c90b6e9731bfb5c9170e8e7853788ac7d5c6100000000001976a9140a57e95b1bf862cf3c03b1c12e602f0442ba71fb88ac00086300000000001976a9145479d3a0114c8cddf9db7a9968c2080310099b5188ac9e106500000000001976a9148c9360e25a76046c63816a38476253821850035588ac035f6600000000001976a914ae84e741f3242a9e211bc36ceebcecff23aaea8c88ac464a6700000000001976a9145f403767b993718e333a14e99dd36d85f7420ddc88acaf656800000000001976a9140382de4d788d215ff9b90fe587dcb61f1069528688ac93f76800000000001976a914e05469fa2c0252b260b6b933ff0bee040a26e29688acd7747200000000001976a91407f021706161902974f6d62eec4612a77ceed20a88acdbcc7700000000001976a91469f3c972ba4798b4e715a9ac551e827eac806eb288aca35b7800000000001976a91491c64c844611e19a145ff07b88ecb699c0b41b1488ac8aac7900000000001976a914069c0fe194b28e2dcb5d259fe358e7ed93ef723c88ac7a247b00000000001976a914c52c18005c5773e5b513aadc31c8a1fed3fe7ad588acbe897e00000000001976a91404a64323ddc9c0385aaeebe53eea5c8f2eed6cf588acb7f78400000000001976a9141dfd861550dc47711a83a8ae2149df95be229e5688ac17548800000000001976a914e69f755fcbb511cfc0d12722c3186e18f3ce589688acef9e8900000000001976a914f19595088875fe2c80a118e4c7e24a82370cfd8e88ac95e08c00000000001976a914080d8830eca132a77539118d0d6a20b4aabd11bb88acd1e58c00000000001976a914202eef8e3daac6e31ce7aae5f8869d9b591bb53e88acd38f8d00000000001976a9141a9b472ccde948f2d3b2c891901a830dd657bebf88acdbb29100000000001976a91483aa26f9c40165c65a7087157ce153ee063a295788ac2a7b9200000000001976a9148b9c0dfbfa34dd5d3f7956d947d41688fe49b52b88acde479300000000001976a914669a712c3073b70b009357f239d32c99e79a431d88ac556f9800000000001976a914193f1a1625ff7d4ffdc5f803648bb4b9bb7bee4988ac9ec49800000000001976a91421bd2c39039c8d2fbee1bd069105de8599d9c26f88acda409a00000000001976a91466e1c71eec5d8b98932176c25d6f8704483303ad88acbcd39b00000000001976a91464eb5bbd8ab5393cc2e22e07ed63d6ebef5a8e3888ac766f9e00000000001976a91494b837cd58ae483b054024f3399572b79b3b6e9388ac9895a000000000001976a914da5837964c5edd60088af63aaaf97f862a6fe4c888ac7ae2a000000000001976a9147f76ed0f250d98df17ddcb127a6f68e62e9e626e88acd83ca100000000001976a914ced2d24d8f9a12afac41ab4ac2aa3dccc5c2412588ac3c03a700000000001976a9141956e5cc5dd13b4843ce273a1d4dc2698e5c5e1e88ac1080a700000000001976a914309467cf0306dd5a9d782132ae020215cc6a3e7e88ac655fa900000000001976a914044ce4661a426460ee298a4fc0be56d9531cab3688acb503ab00000000001976a914754f36491050c2cc39925b15182e0af97c9f724c88ac790fac00000000001976a914e42684e3f0797bb2c126cada06532eb3042d1bbe88ace23daf00000000001976a9149d1f7e94a069768d1a400cd008eb9bfef87eace688ac81b1b400000000001976a9147fcc2253397dcf99e698167d18904a06e57e0dd588ac87b0b500000000001976a914ec8f348275950702f627f83cf31613b035a7ca2e88accdf6b500000000001976a91485a837db1cfb5e0c0ac07b6deab52f96e2aadc9288ac97e0b800000000001976a914e205b664ff00fd8be0a78cbee242d60f83af69e188acb170bd00000000001976a91431f3a36aea42784af75099b3aff7d1c14daa7bbd88ac63f6bd00000000001976a914c29ad49c434362cb2482968fbf00a4ac7abfb61088acb376be00000000001976a914dae631d52e6fc660fac2df92d1358e09a96923ae88acc9c3c100000000001976a914bcf82a713a320942ce47e073787b48e73ed21bc388ac07c0c300000000001976a9143dd2fac77e63571ee7d985864a10291aa494e99d88ac775fc400000000001976a914ee3b1fd4dcea91460a2c55ae8193d6e4836717b688ac5f3fc500000000001976a914f770bd8837733b0e33eb0817ccc087ced11901ca88acd72aca00000000001976a9143a7ab2cefaeb6345aef29bae00530548f65bc3fe88ac916acb00000000001976a914a33e478d9859f0a1c67f63ad0664c7124dacbb5f88ac7f80ce00000000001976a9145d79c60441d02be3fe14028982be5ded8b8f283f88acdbc9ce00000000001976a9147f92bc474f4d80ac06c8f02d57915bf550c8447b88acbb66d200000000001976a914c008f4c184c98d0647002af1bd47511a4017dbd488acc0e9d300000000001976a91495d69476ecd7c53639097cc1258df7a12669efc288ac1802dc00000000001976a914822d54065586040f7e8c15ac4c7053f8b83dfb1f88acfb9de200000000001976a9144d050fa8ad63ffbe59fe12bd5620fda906f031fc88ac8e46e600000000001976a914e1218b2df11277305d402934b52a16d2ff6f594688acfc85ec00000000001976a9147c459dc456f019604044dbbbe96a0108d781d5cd88aceb7fee00000000001976a914afaa77471e0dcec3f4fedd39e667737bf7863d8988aca249f000000000001976a91407c3663f95f69c5a65e3b71d5a874c36ffd1e0c788acefe4f100000000001976a914345281ac54380e98ec5001ecfaea019600da68ba88ac17aaf300000000001976a91462322eadf7c572830bac797a0d43cb0e0afea08988ace8b6ff00000000001976a9140cb6f1c0e7e968a543379bb5ce61d388c127a94488ace9eb0801000000001976a914a91ee9fc58eb73f5982fa6c140395f8c55ffb9fe88ac37e01101000000001976a914f3cd99915d994ff5f7de77c93c6b2463f9ffcd7888ac775a2001000000001976a914a5aef750d2240b740172e349e7a4eb6ad2b608f088acbc6f2301000000001976a914c4ccb62dfe223631f2e86c47c3fd6663dd49d4a288ac2a572801000000001976a914671d41200f07a0f8a52ce16f107749cd4fac6a7588ac42ad2801000000001976a914458db933d5068dd48fca4bc4057ff15f340fa4a588ac80632a01000000001976a914509341b5913426c95a82c45522d723bd0994b17688ac94b93101000000001976a914f8f522d48137b10b0db4b4b702ec0e8936f69b6688acce5b3201000000001976a914d2236f9f3440b372ff6b82adf163497207544a6d88ac6b954d01000000001976a914a1e3eaba6014b21ad41c787a56591b7d952d716788ac7d235201000000001976a914eabe80ff2219adf238cee601eb86296760534d7a88ac31525201000000001976a914a00026d748782cd1a9fd54f59752eb7eba712e3388acb1fe5f01000000001976a914866ae7e812c6ec2b32804bf46cce6388f329265c88ac8c496301000000001976a914a739fc4a3078bded68ca37a8f51aab017565eb7c88acc11a6a01000000001976a91410519f2852d61880aa241691187df0a2fab8631388ac5e576e01000000001976a9144ed72d734ce7080b17203f07263646b81c5f747488ac10487501000000001976a91495ab2e374619aad24375d0e374ee697e9075cb5488acdb097901000000001976a914055dd083a5db9bae3771c7b59443ff65f3bcac3588acae4f8601000000001976a91470e9e21c844119978e6e4121081636a0968b3e1388ac9b5b8b01000000001976a914a2555d2d1a9702a9c550c09e61d6573c510cbd4d88ac26a99601000000001976a9148631b535020200fc5360d42ab2709ecc727ae34888ace0c8a001000000001976a91481721c0ef7136b22fca8ba5386850e53479b53aa88ac1a6cac01000000001976a914cef0bdba115a80124b7aa65ebbbb6fd3d0d52d1d88ac4a8fd201000000001976a9140aca7ad87e8ed090e2d8823028ea1b2253f2db9788acf723da01000000001976a914015dbc353e51ced0794cd834666e715ff649ee6988ac944cda01000000001976a914c0d73c61fc15d89f04595cefcbeeec809ebe939788aca4c0e001000000001976a914d60a86e3722e05fc1edde4b3f18bf37c133169ec88acb730e401000000001976a914bc589b3115f57c322c367f9e044e69e67934cf4588ac6b59f501000000001976a914d4e2b8073c3d2b244547574afde509e7f6db9dd788acf3d60c02000000001976a9143dbb1f0660fa3cc5b4fbe3bd916113794571f1a988ac03191702000000001976a914d1d15aadd0ec795ca48cb21c23e088aecf92385188acfc644102000000001976a914f025c7c67f6b5abbfd852906b6abea1f557ff75088ac1fbd4a02000000001976a914d59f9afd09f248f8b8029b9a87982218487dc8ed88acbb738d02000000001976a91409aa0e2aa937819e450baf8e2a8f5e1d26faf18588ace61fb002000000001976a914c33286f9c471d838cfd03498e846d1d3c03d072088ac4edebf02000000001976a914d9f4097a319c5c7d7ca9024776e84ac482fa127988ac5080c602000000001976a914c168323170c52e9a28c4b069da735bffe8d651ab88ac8373ca02000000001976a9140af27cf6a67f19572ab58189994866937284e13f88acb9820a03000000001976a914a175c7dd65ab6106a6848fde84170f4ac85886e688acab423c03000000001976a9149ef0d190d4e5ebb2f153ba2035cdb65b6ad3a62c88ac91054403000000001976a91466d60d45b8aa75b88fd1bb4d8b8a7950950dc6e588ac0ad45c03000000001976a914391ecba1418302c1822c7c65f9aeb647795ed81c88ac103d6103000000001976a914160767ea477550a19b9fbb7d91cc3047e8adac3588aca7ec6303000000001976a914fa183499bcf8cc1bae1763cf50fd61f9601ec83088aca1db8403000000001976a91497f4fe5557664751495f069ba169ece2c40db1b988acac5fe803000000001976a91491bf8d466b9803625bd48ac340bedd79e207ab5788ac70931004000000001976a914490be16c26e231ade8cb83b06992b63eb0ee552288acf0401c04000000001976a9149b0551defdcbf45fd256b40557bcfb90d39ba73688ac20bd5904000000001976a91497e95d5f876b8091824df8f93b564a70649607b688ac03287704000000001976a9149e964bfd4d25ad4e168b50f86ed2a2f32b5203fe88ac45583a05000000001976a914730f40ca59fa6c61a140ca07db33f33c143a649488ac51495605000000001976a91480207c9f445b47ec5e2a4607861ea2d8fd87718188ac6c77c505000000001976a914bb7a6eaef7fb4e4605f1e0add3dd40cdaa9f578f88ac74ae1006000000001976a9140e395d4568e071968db0e8e775f891021d4c82f088ac83111706000000001976a9141c887da50ed886a6e68a3b01f0b9dde5f807ca6888acc8e38e06000000001976a914835c659778161515a46e4b3f71489b60843344f588acb353d106000000001976a914ba0b189cd808746a1efc9921665dfcfe301222e288acfc14d406000000001976a914838151b12bbba4e843aa878ad4cabea5ddae016488ac5e364e09000000001976a91427cc7e0def9fb434d51d98ee523bcc1e7514390688acfd70a609000000001976a9149c7a3ddca3446a6e36160495a78317fe1cb8f1cc88acbc7bef09000000001976a914201c5f40321160654dc00e8935d4a3e45e97e83088acef64080b000000001976a914c2261cf79f2e50b155ecb913783296facd1ca3dc88ace7fb6910000000001976a91405b9a7fb15a6e9e582fa21295d08f5bfa56bc47a88ac94e8b31d000000001976a9147db536ce753378064c58ccc9cffdc222626fd0f788ac7afdf40000000000434104ffd03de44a6e11b9917f3a29f9443283d9871c9d743ef30d5eddcd37094b64d1b3d8090496b53256786bf5c82932ec23c3b74d9f05a6f95a8b5529352656664bac0000000000000000212055c9360da5fd16f13aaddfbca9553d040e42074dc9cb43e78ee9c4a0c58e406a00000000");

        // incorrect V2 block coinbase
        t1 =
                wireFormatter.fromWireDump("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff48034500030f00456c6967697573005041b94d0182fabe6d6d10b4c21393f05ba6a8f02ee0c0c122766f456052d1ea7c2475e05e15bc957ed10800000000000000002f503253482f00ffffffff183263ff0b000000001976a914762374207b6cf7b3bb10f571e7bfb1806b04e76288acc0ab0404000000001976a914b0cffef229e37d6ed6500791d837c49e801b928e88ac91ba4804000000001976a91412dfd33ce868b93b9a2c1a3d79b9951fc2ce431588ac83a7c909000000001976a91477f64a40bb1d381a2e2043f6a3f0bdf5acfb936d88ac38f00204000000001976a9144a46b2aa6f0a8f62d74732ba8a7107472a71b89a88aceb8da904000000001976a914781da937691186c2ce359845e9c93be73180e98088ac1cf75320000000001976a914559d6989c909b59b20bf10935d052fcd63daf86e88ac49c25922000000001976a914db2f9af40204e79f9c2316c043df0abc2121879388ac6359a613000000001976a914b519aa29ecfdfd49abfc75567d0c010bb1ee30ec88ac56efe213000000001976a91485c9ce4366b559c0a1a65940586d33ce1ccdc31f88ac9d151a04000000001976a914a5630549a8787c78e87d8fb4f3972b315b92648188ac2963520c000000001976a91417ad6ac1056d12cc67661a24f4b30cf989dfff3488acc6730004000000001976a914139944a7a7b21047a6b7b414f53af0915703bee688ac56d5d90a000000001976a914ec2b24ac3b6025fca946b79a16b317c575266e2588ac6daa6e0d000000001976a91494e5178f3cfc56b77f9f1c508b74ed9ff3b558ad88ac1eb91e2c000000001976a9148c620754e10d3002764f397c4abdb14d91e4b32e88ac5ec40104000000001976a9148295ba71f8ccabbaca92d8ec119d63b62fe2e28b88ac10e4d209000000001976a914012eebc0d3353bbd8b7982fded3d9790f2f42bd688aced4a1204000000001976a9146c27249d27cd2b3a44401245bb1c5b04abb6cda888ac73437014000000001976a9141207f81a1c776a1db62b650541921266240966ce88ac7a01530e000000001976a914935409f2ab62c21b7b4445206ff46a5bfd46e58d88ac312f3304000000001976a914926caf7bcfb22997a7674473b689051257ac631488acd2f30604000000001976a91469336adff929bba048efa4565ef567923a163da888ac01805303000000001976a9145399c3093d31e4b0af4be1215d59b857b861ad5d88ac00000000");

        // zero input and full scripts
        t1 =
                wireFormatter.fromWireDump("0100000004de6d9308c2c2a635c2112a048c1659164cebb369523e80efd3501fcbd917bba4b800000000ffffffffde36aa1c56ea7b1e414aff98f575632d4f9649059e9fa065757b3e182f6a8479b900000000ffffffff74522d6e95d4550867a44590ab786e12cad9bdf870f0e91146b14b37db6fefda000000006c493046022100aeae8055d3fba4cb88c7d3a4297e40a40342fd70692466bd88fb2932304e319d022100c9550e15ce512a4391519258153c168a783be61484a502ffd94ade50d9225924012102e4cb4357546bf1058f9370b578b7fcb376c9f52a9e4b1ca59d0d1dac9b41c6f1ffffffff7256eff3e1d7769bf4b43209822d783b50c9fdb58835d6f9b32ffb748dfacc79000000006b483045022100f4a1167bedb9c41f4faf9387be213c2eb2724a2eacb4566596f073f0ebe07fb7022043bfa63e12656d5f61db24bd872b881cfff733fa9f5a66c97cf696225f7a61d40121027cd3a7bbdf60338a820f1d434d8577c1918e4cacef2da3778daeae34c48a9097ffffffff0100e1f5050000000023aa20000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f8700000000");

        t1 =
                wireFormatter.fromWireDump("0100000004346a81e31199ed0b1817eb0b98e37b0cf62e5f5a10d3ca2f7302e2b5d81b1651010000008b48304502202aa1afb0d008296e6d104bdafb659f9d5879b0a0106567b60b79820c4e2cf4d4022100a6a54136d4733342129d52ae2f5be179a0478f7e7f5419d950d738dca41c73570141043594d937d404e29c768bc6056fb3d8c5cbe10c06d1a032492888bb3a26c0d35b4a259c90e04fd0ec1ca76f37c4f556b805d233fe880e5aeefd8163b036579359ffffffff3caf85aed1682729423cce4645c6409688cf823d6ed24f3cb214484db1900fad000000008b483045022049fb2aee7f772e2419a5f02efef4b167f139a878a7ddbbe9cc4a1e29bbfdeabe022100c65c805a08a4ec1354a33cdb96db9944f0fe0d1944e2a76802cbea527e2b5dc701410496d3df72e1bae15b8ddd6f91b8b32133a27bb386fd70f45fab912ac1c0efd93a7ad259860736037e022f08e948ead6311da9c7780d9425122d22e1692fa9d706ffffffff024eec59a2136c97df4a6b5581114ce990b08422541d04186b5b124056385c17010000008b483045022054182db2d48338c10ae97abc9e8f6dd1bdd8e8803f2e8e4d9002cbd78260122b022100b7e9c4e2e6d9fdd58a7b64c84c1330a84bad454205ea78f9fcce00881b9b1605014104f1018fe071a60e46e91669c8aed6515a1efc0773fd78bb896488b8bb79c06b098348057f461f78151d5fc28490a54037504af657b4f52a17530da6f00e426badffffffff1758b0fc10154413ce728ec17ba72d9ccd2cd3227fa0791ba9f1c4365855a4f8000000008a4730440220781c21b30c8e9cc273a5165d7cf12d56769e9a77ebe259c2caa3ac7b1b390ed102200da20e87428599cb27ce0a5a0e9ea477b9c263d6033c5e8a979cfabead64825d0141040bed83947a44f9543cbff95eafb2b0617b981b781ac5cf4acfb05dbb4362b2936e1ebf78081f335fde0c6dd9419c70a64e7e38c224949638b4d222ebbb11ac5bffffffff01000477ed000000001976a914b95a726d2bc3d1eeb1d7ba55a599a3796f05d53a88ac00000000");
        Transaction t2 =
                wireFormatter.fromWireDump("010000000143f93d69060d28b5f4b2ce4aa1468c0b31fd880bd2d03f5810f2b9d99f95cd2b000000008b483045022100d883cbb0f5337d3bc7a4f3b3b62da852e4357f56809e9862029782ed854052fa022052c323b435b1a49dd7b7fbc05ebe7d2f20a1d7de26465348c61cd23c533b3800014104e242011e556662b925d6bafb971247d88f72c32013d5fc04484054d0569b5fa7c394040aa15dee8ddcfe889e32d0b6df871d345cf729ffb0714ab72bbc14ecdaffffffff0240107b8b030000001976a9143daeb981a4bc8e993fdc78584dfb9aa8bc81c57f88ac404b4c00000000001976a914f06f67d6f431893a68a78d8b7088a464c5874ed688ac00000000");

        assertTrue(createScriptValidator(t1, 0, t2.getOutput(1), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest20() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // multisig using invalid keys stored with PUSHDATA1, first seen in block 244029
        Transaction t2 =
                wireFormatter.fromWireDump("0100000001dbb3197449c0ede76729d75fdf845d1b4e367770211a0b30305e17ba7fa57f41010000006b483045022100b6db6e71652d2dcaa818c24d7dcba0ddca9a9a09f45a5a8b175784dde494d78602204e4b1042564da546b032f09eb02d2211f9e8fa3aeb3c987a28962a0a7a6d312b0121037a380cf3628417c275e9010eea2d7b2a05337d38af25054675e3441a43aa4565ffffffff01a0bb0d0000000000fd1901514c78ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff4c78ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff2102323c909b017748294c1d1fb82648b2c2905cd941d9a863e036b3157e8aa85dd353ae00000000");

        Transaction t1 =
                wireFormatter.fromWireDump("0100000001106f0a9fdee8618002cdda338341cedcef81023bfde09d2832d8895c443c9bc4000000004b004930460221008bcf597d5d6a7b2ccf3913983a2d978953e745fa6431331539ded8dc5cbd9f1402210093a4375f9d880fe506e3abcec15a919718e5946b21f823c6d35b539a2386b82701ffffffff0100350c00000000001976a914c676ea066cc2a6b41b0ace8e287fd38030f0b97c88ac00000000");

        assertTrue(createScriptValidator(t1, 0, t2.getOutput(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest21() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // wrong signature order
        Transaction t1 =
                wireFormatter.fromWireDump("01000000012312503f2491a2a97fcd775f11e108a540a5528b5d4dee7a3c68ae4add01dab300000000fdfe000048304502207aacee820e08b0b174e248abd8d7a34ed63b5da3abedb99934df9fddd65c05c4022100dfe87896ab5ee3df476c2655f9fbe5bd089dccbef3e4ea05b5d121169fe7f5f401483045022100f6649b0eddfdfd4ad55426663385090d51ee86c3481bdc6b0c18ea6c0ece2c0b0220561c315b07cffa6f7dd9df96dbae9200c2dee09bf93cc35ca05e6cdf613340aa014c695221031d11db38972b712a9fe1fc023577c7ae3ddb4a3004187d41c45121eecfdbb5b7210207ec36911b6ad2382860d32989c7b8728e9489d7bbc94a6b5509ef0029be128821024ea9fac06f666a4adc3fc1357b7bec1fd0bdece2b9d08579226a8ebde53058e453aeffffffff0180380100000000001976a914c9b99cddf847d10685a4fabaa0baf505f7c3dfab88ac00000000");
        Transaction t2 =
                wireFormatter.fromWireDump("0100000001f7301459ac875fe8a28ccf45ceaab6370a7c5791a4248a1234d54b5ccde2951d000000006c493046022100ec0f98b5d40e80d1ed766e098ea7d19d2238f4d0c3b282d588b8226738b16758022100c8c59c2d04d1234fbd1ba07792bba9477d8934f43b35c02f200c0590b9b059ef0121021803e40e13a344074cb437b8666712400c9ea17dbda076368c7c5a337d567039ffffffff02905f01000000000017a914b1ce99298d5f07364b57b1e5c9cc00be0b04a95487a0860100000000001976a914c9b99cddf847d10685a4fabaa0baf505f7c3dfab88ac00000000");

        assertFalse(createScriptValidator(t1, 0, t2.getOutput(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactionTest() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        // correct signature order
        Transaction t1 =
                wireFormatter.fromWireDump("01000000012312503f2491a2a97fcd775f11e108a540a5528b5d4dee7a3c68ae4add01dab300000000fdfe0000483045022100f6649b0eddfdfd4ad55426663385090d51ee86c3481bdc6b0c18ea6c0ece2c0b0220561c315b07cffa6f7dd9df96dbae9200c2dee09bf93cc35ca05e6cdf613340aa0148304502207aacee820e08b0b174e248abd8d7a34ed63b5da3abedb99934df9fddd65c05c4022100dfe87896ab5ee3df476c2655f9fbe5bd089dccbef3e4ea05b5d121169fe7f5f4014c695221031d11db38972b712a9fe1fc023577c7ae3ddb4a3004187d41c45121eecfdbb5b7210207ec36911b6ad2382860d32989c7b8728e9489d7bbc94a6b5509ef0029be128821024ea9fac06f666a4adc3fc1357b7bec1fd0bdece2b9d08579226a8ebde53058e453aeffffffff0180380100000000001976a914c9b99cddf847d10685a4fabaa0baf505f7c3dfab88ac00000000");
        Transaction t2 =
                wireFormatter.fromWireDump("0100000001f7301459ac875fe8a28ccf45ceaab6370a7c5791a4248a1234d54b5ccde2951d000000006c493046022100ec0f98b5d40e80d1ed766e098ea7d19d2238f4d0c3b282d588b8226738b16758022100c8c59c2d04d1234fbd1ba07792bba9477d8934f43b35c02f200c0590b9b059ef0121021803e40e13a344074cb437b8666712400c9ea17dbda076368c7c5a337d567039ffffffff02905f01000000000017a914b1ce99298d5f07364b57b1e5c9cc00be0b04a95487a0860100000000001976a914c9b99cddf847d10685a4fabaa0baf505f7c3dfab88ac00000000");

        assertTrue(createScriptValidator(t1, 0, t2.getOutputs().get(0), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
    }

    @Test
    public void transactiontest23() throws HyperLedgerException, InterruptedException, ExecutionException, IOException {
        Transaction t1 =
                wireFormatter.fromWireDump("01000000011dd26f565c35e3d1ca2fd9e1c5f8e50db3431f496f0b8964ba74e7f3893ff08a010000006a47304402204427e4bcb2bd48bf5d25dc3c7fde90df5b6e8ad39f62ddf1ad2bf82c33bb7f170220494ac767bbbe269eb8c352c8698ed24f2be09ee5d4e5f6a5d7cf69e709c28f120121021669049d34ea3a8e364710151de215f15a74947ab87b8952ab062ed106c37bf6ffffffff0160e31600000000001976a91446186d1c563e4507b102aa0e5e3b9ef0a9077de388ace2060300");
        Transaction t2 =
                wireFormatter.fromWireDump("010000000174bf689db6885604afec35b058a012097bb3e83a919d2fe0265008dcc0791887010000008c493046022100c4101cee651c0e309db110f67715f70002bfb95a5ab32fd7230798589a846ba0022100b0e96475d8a7cc06932ef113ce35a49d6d2609254987533472c08e8504cdbd7d01410467d0b573e8563fcb78060bcee234de9fc2a92eb54813e5c18d91322afc14a5795ef21dc120d63c3460e02a971ff942ca6cb24cf4df880f17d591f3f7ad6a6951ffffffff02a0fdde05000000001976a9146b4bd968080d950ee8ab4e177c1922860ad6bd6288ac60e31600000000001976a91490ef1b8d547ad139f50390375b695e0046442fbf88ac00000000");

        if (scriptValidationMode == BitcoinValidatorConfig.ScriptEngine.BITCOIN_LIBCONSENSUS) {
            // TODO: this is forking difference between Java and Libconsenus
            // It was likely introduced with the update of Bouncy Castle as it was working in 2013
            // and the block containing this 000000000000036a546044e094db778e1c146f051cd5d0e52fb0e6c43e63ea8c
            // was already on chain
            // will revisit this...
            assertTrue(createScriptValidator(t1, 0, t2.getOutputs().get(1), EnumSet.of(ScriptVerifyFlag.P2SH), true).validate().isValid());
        }
    }
}
