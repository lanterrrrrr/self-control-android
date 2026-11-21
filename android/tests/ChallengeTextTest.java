package com.focusguard.selfcontrol;

import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class ChallengeTextTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
    public static void main(String[] arguments) throws Exception {
        check(ChallengeText.voicePasses("一二三四五六七八九十", "一二三四五六七"));
        check(!ChallengeText.voicePasses("一二三四五六七八九十", "一二三四五六"));
        check(!ChallengeText.voicePasses("一二三四五六七八九十", "一二三四五六七八九十一二三四"));
        check(!ChallengeText.voicePasses("一二三四五六七八九十", " \n。"));
        check(ChallengeText.typingPasses("专注，慢慢来。", "专注 慢慢来"));
        check(!ChallengeText.typingPasses("专注，慢慢来。", "专注 慢慢快"));
        check(!ChallengeText.typingPasses("专注，慢慢来。", "专注 慢慢来123"));
        try (Reader reader = Files.newBufferedReader(Path.of(arguments[0]))) {
            var entries = ChallengeText.loadQuotes(reader);
            check(entries.size() == 200);
            for (var quote : entries) {
                var typing = ChallengeText.typingQuotes(entries, quote, new Random(17));
                check(typing.size() == 3);
                check(typing.stream().distinct().count() == 3);
                check(!typing.contains(quote));
                check(typing.stream().mapToInt(ChallengeText.Quote::length).sum() == 3 * quote.length());
            }
        }
        for (int seed = 0; seed < 1000; seed++) {
            var question = ChallengeText.randomMath(new Random(seed));
            check(question.passes(" " + question.answer() + " "));
            check(!question.passes(Integer.toString(question.answer() + 1)));
        }
        System.out.println("Challenge text, 200 quote groups and 1,000 math cases passed.");
    }
}
