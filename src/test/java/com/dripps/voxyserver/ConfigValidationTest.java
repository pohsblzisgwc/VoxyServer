package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.Comparator;

public final class ConfigValidationTest {
    public static void runAllTests() throws Exception {
        Path directory=Files.createTempDirectory("voxy-config-regression-");
        try {
            Path path=directory.resolve("voxyserver.json");
            var first=VoxyServerConfig.load(path);
            require(first.validate().isEmpty() && Files.exists(path),"first startup did not save validated defaults");
            require(VoxyServerConfig.reload(path).validate().isEmpty(),"default reload failed");
            boundary(path,"hashWorkIntervalTicks",new int[]{0,1,1200,1201});
            boundary(path,"maxHashComputationsPerInterval",new int[]{0,1,4096,4097});
            boundary(path,"maxHashMillisPerInterval",new int[]{0,1,1000,1001});
            boundary(path,"hashRecheckIntervalTicks",new int[]{-1,0,19,20,Integer.MAX_VALUE});
            boundary(path,"manifestRefreshIntervalTicks",new int[]{0,99,100,Integer.MAX_VALUE});
            boundary(path,"lodStreamRadius",new int[]{-1,0,1,4096,4097});
            Files.writeString(path,"null");
            require(VoxyServerConfig.load(path).validate().isEmpty() && Files.readString(path).equals("null"),"null config overwritten");
            require(VoxyServerConfig.reload(path)==null,"reload accepted null");
            Files.writeString(path,"{broken");
            require(VoxyServerConfig.load(path).validate().isEmpty() && Files.readString(path).equals("{broken"),"malformed config overwritten");
            require(VoxyServerConfig.reload(path)==null,"reload accepted malformed JSON");
            System.out.println("PASS ConfigValidationTest: startup/reload/command/save boundaries and invalid-file preservation");
        } finally {
            try(var paths=Files.walk(directory)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
    private static void boundary(Path path,String field,int[] values) throws Exception {
        for(int value:values) {
            var candidate=new VoxyServerConfig(); candidate.getClass().getField(field).setInt(candidate,value);
            boolean valid=candidate.validate().isEmpty();
            String raw=new Gson().toJson(candidate); Files.writeString(path,raw);
            var loaded=VoxyServerConfig.load(path); var reloaded=VoxyServerConfig.reload(path);
            require(loaded.validate().isEmpty(),"startup applied invalid "+field+"="+value);
            require((reloaded!=null)==valid,"startup/reload disagree on "+field+"="+value);
            require(Files.readString(path).equals(raw),"load/reload rewrote input");
            if(!valid) {
                try { new Voxyserver().applyConfigFromCommand(candidate); throw new AssertionError("command accepted invalid config"); }
                catch(IllegalArgumentException expected) { }
                try { candidate.save(path); throw new AssertionError("save accepted invalid config"); }
                catch(IllegalArgumentException expected) { }
                require(Files.readString(path).equals(raw),"invalid save rewrote file");
            } else candidate.requireValid();
        }
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
