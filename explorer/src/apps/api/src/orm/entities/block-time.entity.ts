import {Column, Entity, JoinColumn, ManyToOne, PrimaryColumn} from 'typeorm'
import {assignClean} from '@app/shared/utils';
import {BigNumber} from 'bignumber.js';
import {BigNumberTransformer} from '@app/orm/transformers/big-number.transformer';
import {BlockHeaderEntity} from '@app/orm/entities/block-header.entity'

@Entity('block_time')
export class BlockTimeEntity {

  constructor(data: any) {
    assignClean(this, data);
  }

  @PrimaryColumn({type: 'numeric', readonly: true, transformer: new BigNumberTransformer()})
  number!: BigNumber

  @Column({type: 'bigint', readonly: true})
  timeSeconds!: number

  @ManyToOne(type => BlockHeaderEntity, block => block.txs)
  @JoinColumn({
    name: 'number',
    referencedColumnName: 'number',
  })
  blockHeader!: BlockHeaderEntity

}
