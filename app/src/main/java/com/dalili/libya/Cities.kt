package com.dalili.libya

data class City(val name: String, val lat: Double, val lon: Double)

// Approximate city-centre coordinates, used only to snap routing to the
// nearest road. They are not survey-grade coordinates.
val LIBYAN_CITIES = listOf(
    City("طرابلس", 32.8872, 13.1913),
    City("زليتن", 32.4674, 14.5695),
    City("مصراتة", 32.3754, 15.0924),
    City("بنغازي", 32.1194, 20.0865),
    City("سرت", 31.2089, 16.5887),
    City("الزاوية", 32.7528, 12.7278),
    City("الخمس", 32.6484, 14.2636),
    City("أجدابيا", 30.7555, 20.2262),
    City("طبرق", 32.0767, 23.9614),
    City("درنة", 32.7642, 22.6365),
    City("سبها", 27.0375, 14.4290),
    City("غدامس", 30.1339, 9.4982),
    City("مرزق", 25.9070, 13.9180),
    City("هون", 29.1261, 15.9471),
    City("ترهونة", 32.4326, 13.6330),
    City("غريان", 32.1721, 13.0170),
    City("يفرن", 32.0627, 12.5240),
    City("نالوت", 31.8686, 10.9820),
    City("الكفرة", 24.1995, 23.2999),
    City("البيضاء", 32.7565, 21.7367),
    City("صبراتة", 32.7936, 12.4848),
    City("زوارة", 32.9358, 12.0819),
    City("بني وليد", 31.7568, 14.0025),
    City("الجفرة", 29.0000, 16.0000)
)
