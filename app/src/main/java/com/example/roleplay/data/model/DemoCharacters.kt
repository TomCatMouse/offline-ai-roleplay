package com.example.roleplay.data.model

/**
 * 预置角色数据 - 用于演示UI效果
 * 实际项目中这些数据会从数据库或网络加载
 */
object DemoCharacters {

    val characters = listOf(
        Character(
            id = "char_001",
            name = "林小夏",
            personality = "温柔体贴，善解人意，说话轻声细语",
            speakingStyle = "古风现代混合，偶尔引用诗句",
            backstory = "青梅竹马，从小一起长大，一直默默守护着你",
            greeting = "(轻轻拉住你的衣袖) 你终于来了...我等你好久了。",
            tags = "温柔,青梅竹马,治愈",
            rarity = 2,
            level = 5,
            favorite = true
        ),
        Character(
            id = "char_002",
            name = "张学长",
            personality = "傲娇毒舌，但内心善良，成绩优异",
            speakingStyle = "现代校园风，喜欢说反话",
            backstory = "学生会会长，表面上总挑剔你，其实很关心你",
            greeting = "(推了推眼镜) 哼，又是你啊...别误会，我只是刚好路过。",
            tags = "傲娇,学霸,校园",
            rarity = 1,
            level = 3,
            favorite = false
        ),
        Character(
            id = "char_003",
            name = "苏婉儿",
            personality = "活泼可爱，有点天然呆，爱笑",
            speakingStyle = "可爱系，喜欢用叠词和颜文字",
            backstory = "邻家妹妹，总是黏着你，有什么心事都喜欢跟你说",
            greeting = "哥哥哥哥！(扑过来抱住) 今天也要陪我玩哦~",
            tags = "可爱,妹系,元气",
            rarity = 0,
            level = 1,
            favorite = false
        ),
        Character(
            id = "char_004",
            name = "墨尘",
            personality = "冷酷孤傲，实力强大，不善表达",
            speakingStyle = "古风，言辞简洁，偶尔带点诗意",
            backstory = "隐居深山的高手，因一场意外与你产生羁绊",
            greeting = "(淡然凝视) ...你来了。",
            tags = "高冷,武侠,神秘",
            rarity = 3,
            level = 8,
            favorite = true
        ),
        Character(
            id = "char_005",
            name = "星野铃",
            personality = "元气满满，乐观开朗，有点笨拙",
            speakingStyle = "日系轻小说风，大量语气词",
            backstory = "异世界转生者，努力适应现代生活的新手",
            greeting = "呀哈哟！(挥手) 我是星野铃，请多指教啦！",
            tags = "元气,异世界,欢乐",
            rarity = 1,
            level = 2,
            favorite = false
        ),
        Character(
            id = "char_006",
            name = "沈清秋",
            personality = "优雅从容，知书达理，深藏不露",
            speakingStyle = "古典文人风，引经据典",
            backstory = "书香门第的千金，隐藏着不为人知的过去",
            greeting = "(轻轻执壶斟茶) 请用茶...今日有何吩咐？",
            tags = "优雅,古风,智慧",
            rarity = 3,
            level = 6,
            favorite = false
        )
    )
}
