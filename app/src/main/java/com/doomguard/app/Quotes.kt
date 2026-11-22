package com.doomguard.app

data class Quote(val text: String, val author: String, val themes: List<String> = emptyList())

private fun q(text: String, author: String, vararg themes: String) = Quote(text, author, themes.toList())

object Quotes {
    val THEMES = listOf("reflection", "time", "discipline", "purpose", "attention", "resilience", "morning")

    val builtIn: List<Quote> = listOf(
        // ---- Fyodor Dostoevsky ----
        q("The mystery of human existence lies not in just staying alive, but in finding something to live for.", "Fyodor Dostoevsky", "purpose", "reflection"),
        q("Taking a new step, uttering a new word, is what people fear most.", "Fyodor Dostoevsky", "discipline", "morning"),
        q("Lying to ourselves is more deeply ingrained than lying to others.", "Fyodor Dostoevsky", "attention", "discipline", "reflection"),
        q("Above all, do not lie to yourself.", "Fyodor Dostoevsky", "attention", "discipline", "reflection"),

        // ---- Marcus Aurelius ----
        q("Do not act as if you had ten thousand years to live.", "Marcus Aurelius", "time"),
        q("Waste no more time arguing about what a good man should be. Be one.", "Marcus Aurelius", "discipline", "purpose", "reflection"),
        q("The soul becomes dyed with the colour of its thoughts.", "Marcus Aurelius", "attention", "reflection"),
        q("At dawn, when you have trouble getting out of bed, tell yourself: I am rising to do the work of a human being.", "Marcus Aurelius", "morning", "discipline"),
        q("If it is not right, do not do it; if it is not true, do not say it.", "Marcus Aurelius", "discipline"),
        q("Such as are your habitual thoughts, such also will be the character of your mind.", "Marcus Aurelius", "attention", "reflection"),
        q("It is not death that a man should fear, but he should fear never beginning to live.", "Marcus Aurelius", "time", "purpose", "reflection"),
        q("Do every act of your life as though it were your last.", "Marcus Aurelius", "time", "discipline", "reflection"),

        // ---- Seneca ----
        q("It is not that we have a short time to live, but that we waste a lot of it.", "Seneca", "time"),
        q("While we are postponing, life speeds by.", "Seneca", "time", "discipline", "reflection"),
        q("Begin at once to live, and count each separate day as a separate life.", "Seneca", "time", "morning"),
        q("He who is everywhere is nowhere.", "Seneca", "attention", "reflection"),
        q("If a man knows not to which port he sails, no wind is favourable.", "Seneca", "purpose", "reflection"),
        q("It is not because things are difficult that we do not dare; it is because we do not dare that they are difficult.", "Seneca", "discipline"),
        q("The greatest obstacle to living is expectancy, which hangs upon tomorrow and loses today.", "Seneca", "time"),
        q("Life, if well lived, is long enough.", "Seneca", "time", "purpose"),
        q("No man was ever wise by chance.", "Seneca", "discipline"),
        q("Nothing is a better proof of a well-ordered mind than a man's ability to stop just where he is and pass some time in his own company.", "Seneca", "attention"),
        q("It is not the man who has too little, but the man who craves more, that is poor.", "Seneca", "purpose", "attention"),

        // ---- Epictetus ----
        q("No man is free who is not master of himself.", "Epictetus", "discipline"),
        q("First say to yourself what you would be; and then do what you have to do.", "Epictetus", "purpose", "discipline"),
        q("No great thing is created suddenly.", "Epictetus", "discipline", "resilience"),
        q("If you wish to be a writer, write.", "Epictetus", "discipline", "purpose"),
        q("How long will you wait before you demand the best for yourself?", "Epictetus", "discipline", "morning"),

        // ---- Thoreau, Emerson and other American voices ----
        q("The cost of a thing is the amount of what I will call life which is required to be exchanged for it.", "Henry David Thoreau", "time", "reflection"),
        q("Our life is frittered away by detail.", "Henry David Thoreau", "attention", "reflection"),
        q("It is not enough to be industrious; so are the ants. What are you industrious about?", "Henry David Thoreau", "purpose", "attention", "reflection"),
        q("If one advances confidently in the direction of his dreams, and endeavors to live the life which he has imagined, he will meet with a success unexpected in common hours.", "Henry David Thoreau", "purpose"),
        q("You can't kill time without injuring eternity.", "Henry David Thoreau", "time"),
        q("The question is not what you look at, but what you see.", "Henry David Thoreau", "attention"),
        q("The mass of men lead lives of quiet desperation.", "Henry David Thoreau", "purpose", "reflection"),
        q("Write it on your heart that every day is the best day in the year.", "Ralph Waldo Emerson", "morning"),
        q("Do the thing and you shall have the power.", "Ralph Waldo Emerson", "discipline"),
        q("Concentration is the secret of strength.", "Ralph Waldo Emerson", "attention"),
        q("How we spend our days is, of course, how we spend our lives.", "Annie Dillard", "time", "reflection"),
        q("My experience is what I agree to attend to.", "William James", "attention", "reflection"),
        q("The art of being wise is the art of knowing what to overlook.", "William James", "attention"),
        q("Compared with what we ought to be, we are only half awake.", "William James", "attention", "purpose", "reflection"),
        q("Nothing is so fatiguing as the eternal hanging on of an uncompleted task.", "William James", "discipline"),
        q("The time is always right to do what is right.", "Martin Luther King Jr.", "discipline", "time"),

        // ---- Franklin, Jefferson and the old English moralists ----
        q("Lost time is never found again.", "Benjamin Franklin", "time"),
        q("Dost thou love life? Then do not squander time, for that is the stuff life is made of.", "Benjamin Franklin", "time", "reflection"),
        q("Well done is better than well said.", "Benjamin Franklin", "discipline"),
        q("You may delay, but time will not.", "Benjamin Franklin", "time", "discipline"),
        q("Never put off till tomorrow what you can do today.", "Thomas Jefferson", "discipline", "morning"),
        q("Procrastination is the thief of time.", "Edward Young", "time", "discipline"),
        q("Nothing will ever be attempted, if all possible objections must first be overcome.", "Samuel Johnson", "discipline"),
        q("Our doubts are traitors, and make us lose the good we oft might win, by fearing to attempt.", "William Shakespeare", "discipline"),
        q("Perhaps the most valuable result of all education is the ability to make yourself do the thing you have to do, when it ought to be done, whether you like it or not.", "Thomas H. Huxley", "discipline", "reflection"),

        // ---- Russians, Germans and other Europeans ----
        q("Everyone thinks of changing the world, but no one thinks of changing himself.", "Leo Tolstoy", "discipline", "reflection"),
        q("There is only one time that is important: now.", "Leo Tolstoy", "time", "attention"),
        q("He who has a why to live can bear almost any how.", "Friedrich Nietzsche", "purpose", "resilience"),
        q("Things which matter most must never be at the mercy of things which matter least.", "Goethe (attributed)", "attention", "purpose", "reflection"),
        q("Knowing is not enough; we must apply. Willing is not enough; we must do.", "Johann Wolfgang von Goethe", "discipline"),
        q("All the unhappiness of men arises from one single fact, that they cannot stay quietly in their own chamber.", "Blaise Pascal", "attention", "reflection"),
        q("To live is the rarest thing in the world. Most people exist, that is all.", "Oscar Wilde", "purpose", "reflection"),
        q("Attention is the rarest and purest form of generosity.", "Simone Weil", "attention"),
        q("To see what is in front of one's nose needs a constant struggle.", "George Orwell", "attention", "reflection"),
        q("A wealth of information creates a poverty of attention.", "Herbert Simon", "attention", "reflection"),
        q("When we are no longer able to change a situation, we are challenged to change ourselves.", "Viktor Frankl", "resilience"),

        // ---- Ancient world ----
        q("The first and best victory is to conquer self.", "Plato", "discipline"),
        q("The unexamined life is not worth living.", "Socrates", "purpose", "attention", "reflection"),
        q("We are what we repeatedly do. Excellence, then, is not an act, but a habit.", "Will Durant", "discipline"),
        q("Seize the day, trusting as little as possible in tomorrow.", "Horace", "time"),
        q("A drop of water hollows a stone, not by force but by falling often.", "Ovid", "discipline"),
        q("Nulla dies sine linea: never a day without a line.", "Pliny the Elder", "discipline"),
        q("The mind is not a vessel to be filled but a fire to be kindled.", "Plutarch", "attention", "purpose", "reflection"),
        q("Nothing is enough for the man to whom enough is too little.", "Epicurus", "purpose", "attention", "reflection"),
        q("To see what is right and not to do it is want of courage.", "Confucius", "discipline", "reflection"),
        q("A journey of a thousand miles begins with a single step.", "Lao Tzu", "morning", "discipline"),
        q("Knowing others is intelligence; knowing yourself is true wisdom. Mastering others is strength; mastering yourself is true power.", "Lao Tzu", "discipline"),
        q("All that we are is the result of what we have thought.", "The Dhammapada", "attention"),
        q("Whatsoever thy hand findeth to do, do it with thy might.", "Ecclesiastes 9:10", "discipline"),
        q("So teach us to number our days, that we may apply our hearts unto wisdom.", "Psalm 90:12", "time"),
        q("Keep thy heart with all diligence; for out of it are the issues of life.", "Proverbs 4:23", "attention"),

        // ---- Africa ----
        q("Haraka haraka haina baraka. (Hurry, hurry has no blessing.)", "Swahili proverb", "discipline", "attention"),
        q("Pole pole ndio mwendo. (Slowly, slowly is the way to go.)", "Swahili proverb", "resilience", "discipline"),
        q("Subira huvuta heri. (Patience draws good fortune.)", "Swahili proverb", "resilience"),
        q("The best time to plant a tree was twenty years ago. The second best time is now.", "Proverb", "morning", "discipline"),

        // ---- Added in the reflection pass ----
        q("Give me chastity and continence, but not yet.", "St. Augustine, Confessions", "reflection", "discipline", "time"),
        q("You will never find time for anything. If you want time you must make it.", "Charles Buxton", "time", "reflection", "discipline"),
        q("We are always complaining that our days are few, and acting as though there would be no end of them.", "Seneca", "time", "reflection"),
        q("He that hath no rule over his own spirit is like a city that is broken down, and without walls.", "Proverbs 25:28", "discipline", "reflection"),
        q("Habit is a cable; we weave a thread of it each day, and at last we cannot break it.", "Horace Mann (attributed)", "discipline", "reflection"),
        q("Hold every hour in your grasp. Lay hold of today's task, and you will not need to depend so much upon tomorrow's.", "Seneca", "time", "discipline"),
        q("Nothing, Lucilius, is ours, except time.", "Seneca", "time", "reflection"),
        q("How vain it is to sit down to write when you have not stood up to live.", "Henry David Thoreau", "purpose", "reflection"),
        q("Be not simply good; be good for something.", "Henry David Thoreau", "purpose", "discipline"),
        q("For where your treasure is, there will your heart be also.", "Matthew 6:21", "attention", "reflection"),
        q("For what shall it profit a man, if he shall gain the whole world, and lose his own soul?", "Mark 8:36", "purpose", "reflection"),
        q("In all labour there is profit: but the talk of the lips tendeth only to penury.", "Proverbs 14:23", "discipline", "reflection"),
        q("Haba na haba hujaza kibaba. (Little by little fills the measure.)", "Swahili proverb", "discipline", "resilience"),
        q("Siku njema huonekana asubuhi. (A good day is seen in the morning.)", "Swahili proverb", "morning", "discipline"),
        q("Usipoziba ufa, utajenga ukuta. (If you do not mend the crack, you will build a wall.)", "Swahili proverb", "discipline", "reflection"),
        q("Mtaka cha mvunguni sharti ainame. (One who wants what is under the bed must bend down.)", "Swahili proverb", "discipline", "purpose"),
    )

    fun all(): List<Quote> {
        val hidden = Store.hiddenQuotes()
        val pool = (builtIn + Store.customQuotes()).filter { it.text !in hidden }
        return pool.ifEmpty { builtIn }
    }

    fun pick(theme: String?, exclude: String?): Quote {
        val all = all()
        val themed = if (theme != null) all.filter { theme in it.themes } else all
        val pool = themed.ifEmpty { all }.filter { it.text != exclude }.ifEmpty { all }
        return pool.random()
    }
}
