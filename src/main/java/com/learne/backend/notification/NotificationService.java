package com.learne.backend.notification;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.shared.pagination.CursorTokenService;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final CursorTokenService cursors;
    public NotificationService(JdbcTemplate jdbc, CursorTokenService cursors) { this.jdbc=jdbc; this.cursors=cursors; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=5)
    public Page list(long userId, int size, boolean unreadOnly, String cursor) {
        if(size<1 || size>100) throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid size.");
        Map<String,Object> filters=Map.of("userId",userId,"unreadOnly",unreadOnly);
        List<Object> args=new ArrayList<>(); args.add(userId);
        String boundary="";
        if(cursor!=null) {
            var values=cursors.decode(cursor,"notifications","createdAtDescIdDesc",filters).sortValues();
            try {
                boundary=" and (n.created_at,n.id) < (?,?)";
                args.add(Timestamp.from(Instant.parse(values.get("createdAt")))); args.add(UUID.fromString(values.get("id")));
            } catch(RuntimeException ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid cursor."); }
        }
        args.add(size+1);
        // All resource checks are joins in this single page query, under the count's snapshot.
        var rows=jdbc.query("""
                select n.*, (c.id is not null and c.teacher_id=n.user_id and r.id is not null
                    and r.teacher_id=n.user_id and s.id is not null) as available
                from user_notifications n
                left join courses c on c.id=n.course_id
                left join course_revisions r on r.id=n.revision_id and r.course_id=c.id
                left join course_submission_attempts s on s.id=n.submission_id and s.course_id=c.id
                    and s.revision_id=r.id and s.teacher_id=n.user_id
                where n.user_id=?
                """+(unreadOnly?" and n.read_at is null":"")+boundary+" order by n.created_at desc,n.id desc limit ?",(rs,row)-> {
                    boolean available=rs.getBoolean("available");
                    return new Item(rs.getObject("id",UUID.class),rs.getString("type"),rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("read_at")==null?null:rs.getTimestamp("read_at").toInstant(),
                        rs.getObject("course_id",Long.class),rs.getObject("revision_id",Long.class),rs.getObject("submission_id",UUID.class),
                        available,available?"/teacher/content/courses/"+rs.getLong("course_id"):null);
                },args.toArray());
        boolean hasNext=rows.size()>size;
        var content=List.copyOf(hasNext?rows.subList(0,size):rows);
        String next=null;
        if(hasNext) { var last=content.getLast(); next=cursors.encode("notifications","createdAtDescIdDesc",filters,Map.of("createdAt",last.createdAt(),"id",last.id())); }
        return new Page(content,next,hasNext,size,count(userId));
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=5)
    public Count unreadCount(long userId) { return new Count(count(userId)); }

    @Transactional(isolation=Isolation.REPEATABLE_READ, timeout=5)
    public Read read(long userId, UUID id) {
        var times=jdbc.query("update user_notifications set read_at=coalesce(read_at,current_timestamp) where id=? and user_id=? returning read_at",
            (rs,row)->rs.getTimestamp(1).toInstant(),id,userId);
        if(times.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Notification not found.");
        return new Read(id,times.getFirst(),count(userId));
    }
    private long count(long userId) { return jdbc.queryForObject("select count(*) from user_notifications where user_id=? and read_at is null",Long.class,userId); }
    public record Item(UUID id,String type,String message,Instant createdAt,Instant readAt,Long courseId,Long revisionId,UUID submissionId,boolean resourceAvailable,String actionUrl) {}
    public record Page(List<Item> content,String nextCursor,boolean hasNext,int size,long unreadCount) {}
    public record Count(long unreadCount) {}
    public record Read(UUID id,Instant readAt,long unreadCount) {}
}
