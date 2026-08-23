package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.Topic;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicRepository extends JpaRepository<Topic, Long> {
}
