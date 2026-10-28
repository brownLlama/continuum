# Third-party notices

Continuum includes the following third-party material. Everything not listed here was written for this project.

## Word list: `app/src/main/assets/words.txt`

- **Source:** [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords), `content/2018/en/en_50k.txt`, by Hermit Dave, built from the [OpenSubtitles](https://opus.nlpl.eu/OpenSubtitles.php) corpus.
- **Licence:** [Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)](https://creativecommons.org/licenses/by-sa/4.0/). (FrequencyWords' code is MIT; its word lists are CC BY-SA 4.0.)
- **Changes:** trimmed to the 45,000 most frequent words. At load time the app also raises the counts of common contractions ("don't", "I'm" …).
- `words.txt` is shared under the same CC BY-SA 4.0 licence.

## Common misspellings: `app/src/main/assets/misspellings.txt`

- **Source:** Wikipedia, [Lists of common misspellings/For machines](https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines), by Wikipedia contributors.
- **Licence:** [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
- **Changes:** converted to letters-only keys; entries whose correction is several words were left out.
- `misspellings.txt` is shared under the same CC BY-SA 4.0 licence.

## Emoji list and emoji suggestions: `app/src/main/assets/emoji.tsv`, `app/src/main/assets/emoji_keywords.tsv`

- **Source:** Unicode [emoji-test.txt](https://unicode.org/Public/emoji/16.0/emoji-test.txt) (Emoji 16.0), and the Unicode CLDR 48 English emoji annotations ([`annotations/en.xml`](https://github.com/unicode-org/cldr/blob/release-48/common/annotations/en.xml), [`annotationsDerived/en.xml`](https://github.com/unicode-org/cldr/blob/release-48/common/annotationsDerived/en.xml)).
- **Changes:** `emoji.tsv` is reduced to fully-qualified emoji, one line per group. `emoji_keywords.tsv` picks one emoji per keyword (words of a single meaning; a few common words chosen by hand) and adds plurals.
- **Licence:** Unicode License V3:

```text
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
```

## Libraries inside the app

| Library | Licence |
|---|---|
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [AndroidX Preference](https://developer.android.com/jetpack/androidx/releases/preference) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Material Components for Android](https://github.com/material-components/material-components-android) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Kotlin standard library](https://kotlinlang.org/) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |

## Build tooling

- **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`): [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0), © the original authors (Gradle).

## Colour palettes

The fixed themes use the published colours of [Nord](https://www.nordtheme.com/), [Dracula](https://draculatheme.com/), [Gruvbox](https://github.com/morhetz/gruvbox), [Solarized](https://ethanschoonover.com/solarized/), [Catppuccin](https://catppuccin.com/), [Tokyo Night](https://github.com/enkia/tokyo-night-vscode-theme) and [One Dark](https://github.com/atom/atom/tree/master/packages/one-dark-ui), all MIT-licensed projects. Only colour values are used, no code.

## Services

GIF search uses the [KLIPY](https://klipy.com) API under KLIPY's terms, with each user's own API key.

## Keyboard font: `app/src/main/res/font/maple_mono_*.ttf`

- **Source:** [Maple Mono](https://github.com/subframe7536/maple-font) v7.9, by the Maple Mono Project Authors: the no-ligature build (`MapleMonoNL-TTF.zip`), Regular, Medium and Bold.
- **Changes:** none (files renamed for Android resources).
- **Licence:** SIL Open Font License 1.1:

```text
Copyright 2022 The Maple Mono Project Authors (https://github.com/subframe7536/maple-font)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://openfontlicense.org


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## Names

Minuum is a product of Whirlscape. Continuum is an independent project, not affiliated with or endorsed by Whirlscape.
