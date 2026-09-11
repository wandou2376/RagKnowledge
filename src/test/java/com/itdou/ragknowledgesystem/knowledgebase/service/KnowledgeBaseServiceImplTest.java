package com.itdou.ragknowledgesystem.knowledgebase.service;

import com.itdou.ragknowledgesystem.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBaseStatus;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @Test
    void createShouldPersistAnActiveKnowledgeBase() {
        when(knowledgeBaseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeBaseResponse response = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest("Java knowledge base", "Spring Boot documents")
        );

        assertThat(response.name()).isEqualTo("Java knowledge base");
        assertThat(response.status()).isEqualTo(KnowledgeBaseStatus.ACTIVE);
        verify(knowledgeBaseRepository).save(any());
    }

    @Test
    void getByIdShouldFailWhenKnowledgeBaseDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(knowledgeBaseRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> knowledgeBaseService.getById(id))
                .isInstanceOf(KnowledgeBaseNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
