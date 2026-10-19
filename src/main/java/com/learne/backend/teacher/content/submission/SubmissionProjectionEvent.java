package com.learne.backend.teacher.content.submission;

import java.sql.ResultSet;
import java.sql.SQLException;

record SubmissionProjectionEvent(String kind, long id, Long sourceId, long parentId, int order,
        String a, String b, String c, String d, String e, String f, Integer xp, Boolean flag) {
    static SubmissionProjectionEvent read(ResultSet rs, int row) throws SQLException {
        return new SubmissionProjectionEvent(rs.getString("kind"),rs.getLong("id"),rs.getObject("source_id",Long.class),
                rs.getLong("parent_id"),rs.getInt("order_index"),rs.getString("a"),rs.getString("b"),rs.getString("c"),
                rs.getString("d"),rs.getString("e"),rs.getString("f"),rs.getObject("xp",Integer.class),rs.getObject("flag",Boolean.class));
    }
    void append(SubmissionFingerprint hash) {
        hash.add(kind); hash.add(id); hash.add(sourceId); hash.add(parentId); hash.add(order);
        hash.add(a); hash.add(b); hash.add(c); hash.add(d); hash.add(e); hash.add(f); hash.add(xp); hash.add(flag);
    }
    String fingerprint(com.learne.backend.teacher.content.enrichment.EnrichmentContract contract) {
        var hash = new SubmissionFingerprint(contract,kind); append(hash); return hash.finish();
    }
}
