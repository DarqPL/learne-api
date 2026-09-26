package com.learne.backend.teacher.content.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "topic_revisions")
public class TopicRevision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id")
    private CourseRevision courseRevision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_topic_id")
    private Topic sourceTopic;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @OneToMany(mappedBy = "topicRevision", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LessonRevision> lessons = new ArrayList<>();

    public Long getId() { return id; }
    public CourseRevision getCourseRevision() { return courseRevision; }
    public void setCourseRevision(CourseRevision courseRevision) { this.courseRevision = courseRevision; }
    public Topic getSourceTopic() { return sourceTopic; }
    public void setSourceTopic(Topic sourceTopic) { this.sourceTopic = sourceTopic; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
    public List<LessonRevision> getLessons() { return lessons; }
}
