package cn.linke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static cn.linke.DemoData.*;

/** Narrow content correction for the existing batch; account credentials and workflow stay intact. */
final class RefreshDemoContent {
    static void expectedText(String actual,String oldValue,String newValue,String label) {
        require(Objects.equals(actual,oldValue)||Objects.equals(actual,newValue),"Content was independently edited; retained without overwrite: "+label);
    }
    static ObjectNode mergeFields(JsonNode actual,JsonNode oldFields,JsonNode nextFields,String label) {
        ObjectNode merged=actual.deepCopy();
        for(String key:List.of("title","goal","locationText")) {
            expectedText(text(actual,key),text(oldFields,key),text(nextFields,key),label+":"+key);
            merged.set(key,nextFields.get(key));
        }
        DraftRules.validate(merged,Rules.CATEGORIES);
        return merged;
    }
    static void run(Connection db,JsonNode data,Path output) throws Exception {
        JsonNode previous=JSON.readTree(Files.readString(output.resolve("catalog-before-natural-content.json")));
        require(BATCH.equals(text(previous,"batch"))&&BATCH.equals(text(data,"batch")),"Unexpected content batch");
        var oldAccounts=new HashMap<String,JsonNode>();rows(previous,"accounts").forEach(a->oldAccounts.put(account(a),a));
        var oldRequests=new HashMap<String,JsonNode>();rows(previous,"requests").forEach(r->oldRequests.put(text(r,"key"),r));
        require(oldAccounts.size()==data.path("accounts").size()&&oldRequests.size()==data.path("requests").size(),"Content refresh cannot add or remove fixtures");
        var backup=JSON.createObjectNode();backup.put("batch",BATCH);backup.put("purpose","Before natural wording and neighborhood correction");
        var profileBackups=backup.putArray("profiles");var requestBackups=backup.putArray("requests");var submissionBackups=backup.putArray("submissions");
        int profilesChanged=0,requestsChanged=0,submissionsChanged=0;
        for(var a:rows(data,"accounts")) {
            JsonNode old=oldAccounts.get(account(a));require(old!=null,"Unknown account in refreshed content");
            UUID aid=id(account(a));JsonNode actual;
            try(var s=db.prepareStatement("SELECT to_jsonb(p)::text FROM profiles p JOIN accounts a ON a.id=p.account_id WHERE a.id=? AND a.account_key=? AND a.email=? AND NOT a.operator_granted FOR UPDATE OF a,p")) {
                s.setObject(1,aid);s.setString(2,account(a));s.setString(3,account(a)+DOMAIN);
                try(var r=s.executeQuery()){require(r.next(),"Original fixture account missing: "+account(a));actual=JSON.readTree(r.getString(1));}
            }
            profileBackups.add(actual);
            String name=text(a,"displayName");naturalText(name);
            expectedText(text(actual,"display_name"),text(old,"displayName"),name,account(a)+":displayName");
            String introduction=text(actual,"introduction"),nextIntroduction=introduction;
            if(text(a,"identity").equals("TUTOR")) {
                nextIntroduction=text(a.path("teacher"),"introduction");naturalText(nextIntroduction);
                expectedText(introduction,text(old.path("teacher"),"introduction"),nextIntroduction,account(a)+":introduction");
                Rules.text("introduction",nextIntroduction,1000,true);
            }
            if(!name.equals(text(actual,"display_name"))||!introduction.equals(nextIntroduction)) {
                update(db,"UPDATE profiles SET display_name=?,introduction=?,version=version+1,updated_at=clock_timestamp() WHERE account_id=?",name,nextIntroduction,aid);profilesChanged++;
            }
        }
        for(var r:rows(data,"requests")) {
            String key=text(r,"key");JsonNode old=oldRequests.get(key);require(old!=null,"Unknown request in refreshed content");
            UUID rid=id(key),sid=null;JsonNode actual;
            try(var s=db.prepareStatement("SELECT to_jsonb(r)::text FROM service_requests r WHERE r.id=? AND r.owner_id=? FOR UPDATE")) {
                s.setObject(1,rid);s.setObject(2,id(text(r,"owner")));
                try(var result=s.executeQuery()){require(result.next(),"Original fixture request missing: "+key);actual=JSON.readTree(result.getString(1));}
            }
            requestBackups.add(actual);
            var next=mergeFields(actual.path("fields"),old.path("fields"),r.path("fields"),key);
            naturalText(text(next,"title"));naturalText(text(next,"goal"));
            require(text(next,"locationText").startsWith("深圳市坪山区")&&text(next,"locationText").length()>8,"Missing neighborhood");
            if(!actual.path("fields").equals(next)){update(db,"UPDATE service_requests SET fields=CAST(? AS jsonb),version=version+1 WHERE id=?",encode(next),rid);requestsChanged++;}
            if(actual.hasNonNull("latest_submission_id")) {
                sid=UUID.fromString(text(actual,"latest_submission_id"));
                require(sid.equals(id("submission:"+key)),"Request has a later user submission; retain it before reviewing content: "+key);
                JsonNode submission;
                try(var s=db.prepareStatement("SELECT to_jsonb(s)::text FROM request_submissions s WHERE s.id=? AND s.request_id=? FOR UPDATE")) {
                    s.setObject(1,sid);s.setObject(2,rid);try(var result=s.executeQuery()){require(result.next(),"Original submission missing");submission=JSON.readTree(result.getString(1));}
                }
                submissionBackups.add(submission);
                var nextSubmission=mergeFields(submission.path("fields"),old.path("fields"),r.path("fields"),key+":submission");
                String name=text(r,"publisherDisplayName");naturalText(name);
                expectedText(text(submission,"publisher_display_name"),text(old,"publisherDisplayName"),name,key+":publisher");
                if(!submission.path("fields").equals(nextSubmission)||!name.equals(text(submission,"publisher_display_name"))) {
                    update(db,"UPDATE request_submissions SET fields=CAST(? AS jsonb),publisher_display_name=?,version=version+1 WHERE id=?",encode(nextSubmission),name,sid);submissionsChanged++;
                }
                update(db,"UPDATE review_decisions SET reason='请补充具体学习目标和授课安排后重新提交。' WHERE submission_id=? AND reason='合成测试驳回：请补充具体学习目标和授课安排后重新提交。'",sid);
            }
        }
        Path backupPath=output.resolve("natural-content-backup.local.json");
        if(!Files.exists(backupPath))Files.writeString(backupPath,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(backup),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        db.commit();
        // Update only the human-readable labels in the local credential index; passwords remain unchanged.
        Path credentials=output.resolve("credentials.local.json");
        JsonNode credentialData=JSON.readTree(Files.readString(credentials));
        var names=new HashMap<String,String>();rows(data,"accounts").forEach(a->names.put(account(a),text(a,"displayName")));
        for(var credential:credentialData)if(names.containsKey(account(credential)))((ObjectNode)credential).put("displayName",names.get(account(credential)));
        Path pending=output.resolve("credentials.labels.pending.local.json");
        Files.writeString(pending,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(credentialData),StandardCharsets.UTF_8);
        Files.move(pending,credentials,StandardCopyOption.REPLACE_EXISTING);
        System.out.println("CONTENT_REFRESHED profiles="+profilesChanged+" requests="+requestsChanged+" submissions="+submissionsChanged+"; credentials, workflow states and expiry dates retained");
    }
    private RefreshDemoContent() {}
}
