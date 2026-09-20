package com.itjyc.travel.controller;

import com.itjyc.travel.memory.SessionStore;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话历史管理接口：保存 / 列表 / 加载 / 删除。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionStore store;

    public SessionController(SessionStore store) {
        this.store = store;
    }

    /** 保存会话历史（前端每次对话后同步）。 */
    @PutMapping("/{id}")
    public void save(@PathVariable String id, @RequestBody SaveRequest req) {
        store.save(id, req.title(), req.messages());
    }

    /** 会话列表（按最后更新时间倒序）。 */
    @GetMapping
    public List<SessionStore.SessionSummary> list() {
        return store.list();
    }

    /** 加载某会话的历史消息。 */
    @GetMapping("/{id}")
    public JsonNode load(@PathVariable String id) {
        return store.load(id);
    }

    /** 删除会话。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        store.delete(id);
    }

    public record SaveRequest(String title, JsonNode messages) {}
}
