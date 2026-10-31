package eu.eahub.entities.stats;

import eu.eahub.entities.core.PersonaEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "TW_ENTRY", schema = "stats")
public class TwEntryEntity implements TwScorecard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String token;

    @ManyToOne
    @JoinColumn(name = "EVENT_ID", nullable = false)
    private TwEventEntity event;

    @ManyToOne
    @JoinColumn(name = "PERSONA_ID", nullable = false)
    private PersonaEntity persona;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private boolean counted;

    private int rnk;

    private int done;

    private int quit;

    private int type;

    private int score;

    private int club;

    private int strokes;

    private int putts;

    private int holes;

    private int eags;

    private int bird;

    private int aces;

    private int gir;

    private int lput;

    private int drvs;

    private int frwy;

    private int ldrv;

    private int shtc;

    private int pars;

    private int sbog;

    private int dbog;

    private int tbog;

    private int comp;

    private Integer finalRank;

    private Long prize;
}
