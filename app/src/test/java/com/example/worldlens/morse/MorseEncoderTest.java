package com.example.worldlens.morse;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MorseEncoderTest {

    @Test
    public void testTextToMorseString_singleWord() {
        String morse = MorseEncoder.textToMorseString("SOS");
        assertEquals("... --- ...", morse);
    }

    @Test
    public void testTextToMorseString_singleLetterE() {
        assertEquals(".", MorseEncoder.textToMorseString("E"));
    }

    @Test
    public void testTextToMorseString_singleLetterT() {
        assertEquals("-", MorseEncoder.textToMorseString("T"));
    }

    @Test
    public void testTextToMorseString_referenceHowAreYou() {
        // Matching reference audio (morse.wav)
        String morse = MorseEncoder.textToMorseString("HOW ARE YOU");
        assertEquals(".... --- .-- / .- .-. . / -.-- --- ..-", morse);
    }

    @Test
    public void testTextToMorseString_multipleWords() {
        String morse = MorseEncoder.textToMorseString("HELLO WORLD");
        assertEquals(".... . .-.. .-.. --- / .-- --- .-. .-.. -..", morse);
    }

    @Test
    public void testTextToMorseString_withSlash() {
        String morse = MorseEncoder.textToMorseString("HI / YOU");
        assertEquals(".... .. / -.-- --- ..-", morse);
    }

    @Test
    public void testTextToMorseString_digits() {
        String morse = MorseEncoder.textToMorseString("A1");
        assertEquals(".- .----", morse);
    }

    @Test
    public void testTextToMorseString_unsupportedCharactersIgnored() {
        String morse = MorseEncoder.textToMorseString("#Hello, World!");
        assertEquals(".... . .-.. .-.. --- / .-- --- .-. .-.. -..", morse);
    }

    @Test
    public void testTextToMorseString_emptyAndNull() {
        assertEquals("", MorseEncoder.textToMorseString(""));
        assertEquals("", MorseEncoder.textToMorseString("   "));
        assertEquals("", MorseEncoder.textToMorseString(null));
        assertEquals("", MorseEncoder.textToMorseString("@#$%^&"));
    }

    @Test
    public void testTextToMorseWords_structure() {
        List<List<String>> words = MorseEncoder.textToMorseWords("HOW ARE YOU");
        assertEquals(3, words.size());

        // HOW: H(....), O(---), W(.--)
        assertEquals(3, words.get(0).size());
        assertEquals("....", words.get(0).get(0));
        assertEquals("---", words.get(0).get(1));
        assertEquals(".--", words.get(0).get(2));

        // ARE: A(.-), R(.-.), E(.)
        assertEquals(3, words.get(1).size());
        assertEquals(".-", words.get(1).get(0));
        assertEquals(".-.", words.get(1).get(1));
        assertEquals(".", words.get(1).get(2));

        // YOU: Y(-.--), O(---), U(..-)
        assertEquals(3, words.get(2).size());
        assertEquals("-.--", words.get(2).get(0));
        assertEquals("---", words.get(2).get(1));
        assertEquals("..-", words.get(2).get(2));
    }

    @Test
    public void testTextToMorseString_requiredTestCases() {
        assertEquals("... --- ...", MorseEncoder.textToMorseString("SOS"));
        assertEquals(".... . .-.. .-.. ---", MorseEncoder.textToMorseString("HELLO"));
        assertEquals(".-. . -.. / -.-. .- .-.", MorseEncoder.textToMorseString("RED CAR"));
        assertEquals("-- .- -. / .-.. -.-- .. -. --. / -.. --- .-- -.", MorseEncoder.textToMorseString("MAN LYING DOWN"));
    }

    @Test
    public void testParseMorseCodeToWords() {
        List<List<String>> words = MorseEncoder.parseMorseCodeToWords(".-. . -.. / -.-. .- .-.");
        assertEquals(2, words.size());

        // RED
        assertEquals(3, words.get(0).size());
        assertEquals(".-.", words.get(0).get(0));
        assertEquals(".", words.get(0).get(1));
        assertEquals("-..", words.get(0).get(2));

        // CAR
        assertEquals(3, words.get(1).size());
        assertEquals("-.-.", words.get(1).get(0));
        assertEquals(".-", words.get(1).get(1));
        assertEquals(".-.", words.get(1).get(2));
    }
}
