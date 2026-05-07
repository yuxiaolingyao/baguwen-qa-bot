package com.baguwen.bot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baguwen.bot.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
}
