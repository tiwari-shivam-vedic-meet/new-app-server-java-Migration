package com.vedicmeet.appserver.content;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Direct Java translation of Node {@code utils/functions/yoga.js}. */
@Service
public class YogaService {
    private static final Set<Integer> KENDRA = Set.of(1, 4, 7, 10);
    private static final Set<String> AUSPICIOUS = Set.of("Jupiter", "Venus", "Mercury", "Moon");

    public List<Document> applied(List<Map<String, Object>> raw) {
        List<Planet> planets = raw == null ? List.of() : raw.stream().map(Planet::from).toList();
        List<Yoga> yogas = definitions();
        List<Document> result = new ArrayList<>();
        for (Yoga yoga : yogas) if (yoga.test.test(planets)) result.add(yoga.output());
        return result;
    }

    private List<Yoga> definitions() {
        return List.of(
                yoga("Veshi Yoga", List.of("Sun Yogas"), List.of("health", "career", "relationship"), "",
                        "Apart from the Moon, the auspicious planet in a house away from the Sun, Vaishi Yug occurs. People born in this house are happy, courageous, prosperous, generous, and loved by the ruling class. They are always kind-spoken, intelligent, and have a tall body.", null,
                        p -> { Planet sun = find(p, "Sun"); if (sun == null) return false;
                            int next = sun.house % 12 + 1, previous = (sun.house - 2 + 12) % 12 + 1;
                            return p.stream().anyMatch(x -> AUSPICIOUS.contains(x.name) && !"Moon".equals(x.name)
                                    && (x.house == next || x.house == previous)); }),
                yoga("Vashi Yoga", List.of("Sun Yogas"), null, "",
                        "When any true auspicious planet other than the Moon is in the 12th house from the Sun, then the person born in it becomes wealthy and influential.", null,
                        p -> { Planet sun = find(p, "Sun"); if (sun == null) return false;
                            int house = (sun.house + 11) % 12 + 1;
                            return p.stream().anyMatch(x -> AUSPICIOUS.contains(x.name) && !"Moon".equals(x.name) && x.house == house); }),
                yoga("Ubhayachari Yoga", List.of("Sun Yogas"), null, "",
                        "The auspicious planet except the Moon is in the 2nd and 12th house from the Sun, then the person gets rights like a king and is respected, rich, and happy. If an inauspicious planet is placed, then the person is humiliated and suffers from mental pain. The person will also lack money and suffer from bad luck.", null,
                        p -> { Planet sun = find(p, "Sun"); if (sun == null) return false;
                            int second = sun.house % 12 + 1, twelfth = (sun.house + 11) % 12 + 1;
                            return p.stream().anyMatch(x -> AUSPICIOUS.contains(x.name) && !"Moon".equals(x.name)
                                    && (x.house == second || x.house == twelfth)); }),
                yoga("Budh Aditya Yoga", List.of("Sun Yogas"), null, "",
                        "The Sun and Mercury are in the same house, then the person is intelligent, respectable, skilled in work, rich and happy.", null,
                        p -> sameHouse(find(p, "Sun"), find(p, "Mercury"))),
                yoga("Sunfa Yoga", List.of("Moon Yogas"), null, "",
                        "If any auspicious planet other than the Sun is in the second house of the Moon, then the person is respected like a king. A person born in this Yoga will be respected, wealthy, and prestigious.", null,
                        p -> { Planet moon = required(p, "Moon"); int h = moon.house == 12 ? 1 : moon.house + 1;
                            return p.stream().anyMatch(x -> x.house == h && AUSPICIOUS.contains(x.name) && !"Sun".equals(x.name)); }),
                yoga("Anfa Yoga", List.of("Moon Yogas"), null, "",
                        "If other auspicious planets except the Sun are in the 12th house from the Moon, then one will get respect from the king and be disease-free, virtuous, and happy.", null,
                        p -> { Planet moon = required(p, "Moon"); int h = moon.house == 1 ? 12 : moon.house - 1;
                            return p.stream().anyMatch(x -> x.house == h && AUSPICIOUS.contains(x.name) && !"Sun".equals(x.name)); }),
                yoga("Durudhara Yoga", List.of("Moon Yogas"), null, "",
                        "This Yug is formed if any other planet except the Sun is situated in the second and twelfth houses from the Moon.\nA person with this Yug is blessed with all happiness, enjoys happiness, and is obedient. He is rich and has the luxury of servants, vehicles, etc.", null,
                        p -> { Planet moon = required(p, "Moon"); int second = moon.house == 12 ? 1 : moon.house + 1;
                            int twelfth = moon.house == 1 ? 12 : moon.house - 1;
                            return p.stream().anyMatch(x -> x.house == second && x.house == twelfth && !"Sun".equals(x.name)); }),
                yoga("Kemadruma Yoga", List.of("Moon Yogas"), null, "",
                        "If there is no other planet in the first, second or twelfth house from the Moon or the center of the Lagna, then Kemadruma Yug occurs. This is an inauspicious combination. In this Yoga, the person is poor and unintelligent, suffering from adversities and sorrows. If this Yoga is present in the horoscope, then other auspicious Yugs are destroyed.", null,
                        p -> { Planet moon = required(p, "Moon"); int second = moon.house == 12 ? 1 : moon.house + 1;
                            int twelfth = moon.house == 1 ? 12 : moon.house - 1;
                            return p.stream().noneMatch(x -> x.house == moon.house || x.house == second || x.house == twelfth); }),
                yoga("Gajakesari Yoga", List.of("Moon Yogas"), null, "",
                        "If Jupiter is situated in the center of the Moon (1,4,7,10). If Mercury or Venus has a conjunction or aspect with the Moon. (Parashar) then Gajakesari Yug is formed. The person born in this field will do great work and can speak skillfully during meetings. In this, strength in Chandasa is necessary. Other planets should also be strong. Some learned astrologers consider it Gajakesari Yoga  when Jupiter is situated in Kendra from the Lagna.",
                        "Jupiter is in Kendra, i.e. the Ascendant or the first, fourth, seventh, and tenth houses from the position of the planet Moon.",
                        p -> { Planet moon = required(p, "Moon"), jupiter = required(p, "Jupiter");
                            Planet mercury = required(p, "Mercury"), venus = required(p, "Venus");
                            boolean kendra = KENDRA.stream().map(k -> (moon.house + k - 1) % 12 + 1).anyMatch(h -> h == jupiter.house);
                            return kendra || mercury.house == moon.house || venus.house == moon.house; }),
                yoga("Chandradhi Yoga", List.of("Moon Yogas"), null, "",
                        "If the Moon has an auspicious house in the 6th,\n7th and 8th house then the person will be the army chief. Longevity, freedom from diseases becomes the king's minister, but it depends on the names of the planets.", "",
                        p -> { Planet moon = required(p, "Moon"); Set<Integer> houses = Set.of(
                                (moon.house + 5) % 12 + 1, (moon.house + 6) % 12 + 1,
                                (moon.house + 7) % 12 + 1);
                            return p.stream().anyMatch(x -> houses.contains(x.house) && AUSPICIOUS.contains(x.name)); }),
                yoga("Amala Yoga", List.of("Moon Yogas"), null, "",
                        "If Venus is strong in the 10th then Amala Yoga is formed in which Kirti Yoga is added.The person is fond of his family, friends and people, generous, charitable, philanthropic, religious and rich.",
                        "Natural benefic (Jupiter, Venus or  Mercury) is posited in the 10th house from Lagna or Moon with no presence of natural malefic planets in the 10th house.",
                        p -> { Planet moon = required(p, "Moon"), asc = required(p, "Ascendant");
                            int mh = (moon.house + 9) % 12 + 1, ah = (asc.house + 9) % 12 + 1;
                            return p.stream().anyMatch(x -> (x.house == mh || x.house == ah) && AUSPICIOUS.contains(x.name)); }),
                signYoga("Hansa Yoga (Jupiter)", "Jupiter", Set.of("Pisces", "Sagittarius"),
                        "When Jupiter is in its own sign (Sagittarius or Pisces) or is exalted and is situated in the center from the Lagna and lagna is also strong, then it is known as Hans Yoga.",
                        "The person born in this Yoga is a ruler, a high official in government service and has an excellent personality. He was fond of good food and knowledgeable about religious scriptures.\nIs blessed with all happiness.",
                        "Jupiter has to be in Pisces or Sagittariu and located in a Kendra house that is first, fourth, seventh and tenth house"),
                signYoga("Malavya Yoga", "Venus", Set.of("Taurus", "Libra", "Pisces"),
                        "If Venus is situated in its own sign (Taurus or Libra) or in its exalted sign (Pisces) in the center from the ascendant and the ascendant is strong then Malavya Yoga occurs.",
                        "In this Yoga, the person has beautiful eyes, is intelligent, powerful, has children, wife, riders and servants. Capable of giving advice, generous and long lived.",
                        "Venus has to be in Taurus, Libra or Pisces and located in first house, fourth house, seventh house or tenth house for MY to occur."),
                signYoga("Bhadra Yoga", "Mercury", Set.of("Gemini", "Virgo"),
                        "If Buddha is in the center from the ascendant located in its own sign (Gemini or Virgo) or exalted sign (Virgo) and the ascendant is strong then Bhadra Yoga occurs.",
                        "The face of the person born in this is like that of a lion, from knees to toes is like that of an elephant, the chest is big and the hands and feet are soft. Such a person is sensual, learned, andIs adept in Yoga. When Virgo or Gemini zodiac sign is placed in the center, then the other zodiac sign will also be in the center, that is, Mercury, being the lord of two zodiac signs, will also be the lord of the center and it will have the defect of being the lord of the center. If the second zodiac sign will fall in the seventh house, then it will have the defect of being the lord of the Maraka house. will be. Inauspiciousness will go away. In such a situation, the results will be given on the basis of where the Buddha is situated, whether it is blessed or visible or what is the state etc.",
                        "Mercury is in the sign of Gemini or Virgo and is located in a Kendra house, i.e. first, fourth, seventh and the tenth house."),
                signYoga("Ruchak Yoga", "Mars", Set.of("Aries", "Scorpio", "Capricorn"), "",
                        "If Mars is situated in Aries or Scorpio (own sign) or Capricorn (exalted sign) then the ascendant is situated in\nIf it is in the center and the ascendant is strong then Ruchak Yoga is formed. The person born in this is famous for his courage, bravery, military leadership and good qualities. The person is long-lived, attractive and has a well-built body.",
                        "The planet Mars is placed in Aries, Scorpio, or Capricorn and in one of the Kendra houses that is 1st,\n4th,\n7th or 10th house then Ruchaka Yoga is formed"),
                signYoga("Shasha Yoga", "Saturn", Set.of("Capricorn", "Aquarius", "Libra"),
                        "If Saturn is situated in its own sign (Capricorn or Aquarius) or in the exalted sign Libra, in the center from the ascendant and the ascendant is strong, then Shasha Yoga occurs.",
                        "The person gives advice, is the head of the village, has a hard heart, uses the property of others, lives in the company of women, is rich, happy and respectable.",
                        "Saturn has to be in Capricorn or Aquarius or Libra in first house, fourth house, seventh house or tenth house for Sasha Yoga to occur."));
    }

    private Yoga signYoga(String label, String planet, Set<String> signs, String title,
                          String description, String additional) {
        return yoga(label, List.of("Panch Mahapurush Yoga"), null, title, description, additional,
                p -> { Planet value = required(p, planet); return signs.contains(value.sign) && KENDRA.contains(value.house); });
    }

    private Yoga yoga(String label, List<String> category, List<String> extra, String title,
                      String description, String additional, Predicate<List<Planet>> test) {
        return new Yoga(label, category, extra, title, description, additional, test);
    }

    private Planet find(List<Planet> planets, String name) {
        return planets.stream().filter(p -> name.equals(p.name)).findFirst().orElse(null);
    }

    private Planet required(List<Planet> planets, String name) {
        Planet value = find(planets, name);
        if (value == null) throw new IllegalArgumentException("PLANET_NOT_FOUND:" + name);
        return value;
    }

    private boolean sameHouse(Planet a, Planet b) { return a != null && b != null && a.house == b.house; }

    private record Planet(String name, int house, String sign) {
        static Planet from(Map<String, Object> raw) {
            Object h = raw == null ? null : raw.get("house");
            int house = h instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(h));
            return new Planet(String.valueOf(raw.get("name")), house,
                    raw.get("sign") == null ? null : String.valueOf(raw.get("sign")));
        }
    }

    private record Yoga(String label, List<String> category, List<String> extraCategory,
                        String title, String description, String additionalInformation,
                        Predicate<List<Planet>> test) {
        Document output() {
            Document d = new Document("label", label).append("category", category);
            if (extraCategory != null) d.append("extra_category", extraCategory);
            d.append("title", title).append("description", description);
            if (additionalInformation != null) d.append("additionalInformation", additionalInformation);
            return d;
        }
    }
}
